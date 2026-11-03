package fruition;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiPredicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.properties.CanBeAnnotated.Predicates.metaAnnotatedWith;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static com.tngtech.archunit.library.freeze.FreezingArchRule.freeze;

/**
 * 지금 코드가 지키는 패키지 의존 규칙을 고정한다. 규칙을 깨야 한다면 이 테스트를 먼저 고친다.
 * freeze(...) 규칙은 현재 위반을 src/test/resources/archunit_store에 기록해 두고 새 위반만 실패시킨다.
 * store 생성·갱신(freeze 규칙 추가·변경, 위반 수정 시): archunit.properties의 allowStoreUpdate와 freeze.refreeze를 잠시 true로 두고 이 테스트만 실행한 뒤 되돌려 store diff를 커밋한다.
 */
@AnalyzeClasses(packages = "fruition", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureRulesTest {

	/** controller 패키지에 있거나 @Controller/@RestController가 붙은 클래스(평평한 패키지의 컨트롤러 포함). */
	private static final DescribedPredicate<JavaClass> CONTROLLER = resideInAPackage("..controller..")
			.or(metaAnnotatedWith(Controller.class))
			.as("controllers");

	/** ..repository.. 패키지, @Repository, Spring Data Repository 하위 타입(평평한 패키지의 MeetingRepository 등 포함). */
	private static final DescribedPredicate<JavaClass> REPOSITORY = resideInAPackage("..repository..")
			.or(metaAnnotatedWith(Repository.class))
			.or(assignableTo(org.springframework.data.repository.Repository.class))
			.as("repositories");

	private static final DescribedPredicate<JavaClass> DTO = resideInAPackage("..dto..");

	@ArchTest
	static final ArchRule service와_repository는_controller에_의존하지_않는다 = noClasses()
			.that().resideInAPackage("..service..").or(REPOSITORY)
			.should().dependOnClassesThat(CONTROLLER);

	@ArchTest
	static final ArchRule domain은_controller와_service에_의존하지_않는다 = noClasses()
			.that().resideInAPackage("..domain..")
			.should().dependOnClassesThat(CONTROLLER.or(resideInAPackage("..service..")));

	@ArchTest
	static final ArchRule shared는_core에_의존하지_않는다 = noClasses()
			.that().resideInAPackage("fruition.shared..")
			.should().dependOnClassesThat().resideInAPackage("fruition.core..");

	@ArchTest
	static final ArchRule shared_패키지_사이에_순환이_없다 = slices()
			.matching("fruition.shared.(*)..")
			.should().beFreeOfCycles();

	@ArchTest
	static final ArchRule controller에는_Transactional을_두지_않는다 = noClasses()
			.that(CONTROLLER)
			.should().beAnnotatedWith(Transactional.class)
			.orShould().beAnnotatedWith(jakarta.transaction.Transactional.class);

	@ArchTest
	static final ArchRule controller_메서드에도_Transactional을_두지_않는다 = methods()
			.that().areDeclaredInClassesThat(CONTROLLER)
			.should().notBeAnnotatedWith(Transactional.class)
			.andShould().notBeAnnotatedWith(jakarta.transaction.Transactional.class);

	// ---- 아래는 현재 위반이 있어 기록(freeze)해 두고 새 위반만 막는 규칙 ----
	// 위반은 "클래스 -> 대상 클래스" 한 줄(같은 쌍은 한 번)로 낸다. 라인 번호·시그니처가 없어 생성자 파라미터나 무관한 줄을 바꿔도 store가 깨지지 않는다.

	@ArchTest
	static final ArchRule controller는_repository를_직접_쓰지_않는다 = freeze(classes()
			.that(CONTROLLER)
			.should(notDependOn("not depend on repositories", (origin, target) -> REPOSITORY.test(target))));

	@ArchTest
	static final ArchRule domain은_dto에_의존하지_않는다 = freeze(classes()
			.that().resideInAPackage("..domain..")
			.should(notDependOn("not depend on dto", (origin, target) -> DTO.test(target))));

	// fruition.core 도메인 간 순환은 freeze하지 않는다: 탐지 상한(100개)에서 잘린 순환마다 의존 목록이 통째로 기록돼 store가 9천 줄을 넘는다.

	@ArchTest
	static final ArchRule Autowired_필드_주입을_쓰지_않는다 = freeze(classes()
			.should(new ArchCondition<JavaClass>("not have fields annotated with @Autowired") {
				@Override
				public void check(JavaClass javaClass, ConditionEvents events) {
					for (JavaField field : javaClass.getFields()) {
						if (field.isAnnotatedWith(Autowired.class)) {
							events.add(SimpleConditionEvent.violated(field, javaClass.getName() + "." + field.getName()));
						}
					}
				}
			}));

	@ArchTest
	static final ArchRule 다른_도메인의_repository를_직접_쓰지_않는다 = freeze(classes()
			.that().resideInAPackage("fruition.core..")
			.should(notDependOn("not depend on repositories of other fruition.core modules", (origin, target) -> {
				String targetModule = coreModule(target.getPackageName());
				return targetModule != null && REPOSITORY.test(target)
						&& !targetModule.equals(coreModule(origin.getPackageName()));
			})));

	private static final Pattern CORE_MODULE = Pattern.compile("^fruition\\.core\\.([^.]+).*$");

	private static String coreModule(String packageName) {
		Matcher m = CORE_MODULE.matcher(packageName);
		return m.matches() ? m.group(1) : null;
	}

	private static JavaClass outermost(JavaClass c) {
		JavaClass base = c.getBaseComponentType();
		while (base.getEnclosingClass().isPresent()) {
			base = base.getEnclosingClass().get();
		}
		return base;
	}

	/** origin(바깥 클래스 기준)이 forbidden인 대상에 의존하면 "origin -> target"을 쌍마다 한 번 위반으로 낸다. */
	private static ArchCondition<JavaClass> notDependOn(String description, BiPredicate<JavaClass, JavaClass> forbidden) {
		return new ArchCondition<>(description) {
			@Override
			public void check(JavaClass javaClass, ConditionEvents events) {
				JavaClass origin = outermost(javaClass);
				Set<String> reported = new TreeSet<>();
				for (Dependency d : javaClass.getDirectDependenciesFromSelf()) {
					JavaClass target = outermost(d.getTargetClass());
					if (!target.equals(origin) && forbidden.test(origin, target)
							&& reported.add(origin.getName() + " -> " + target.getName())) {
						events.add(SimpleConditionEvent.violated(javaClass, origin.getName() + " -> " + target.getName()));
					}
				}
			}
		};
	}

}
