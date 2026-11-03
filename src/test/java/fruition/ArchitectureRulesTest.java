package fruition;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.conditions.ArchConditions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.properties.CanBeAnnotated.Predicates.metaAnnotatedWith;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static com.tngtech.archunit.library.freeze.FreezingArchRule.freeze;

/**
 * 지금 코드가 지키는 패키지 의존 규칙을 고정한다. 규칙을 깨야 한다면 이 테스트를 먼저 고친다.
 * freeze(...) 규칙은 현재 위반을 src/test/resources/archunit_store에 기록해 두고 새 위반만 실패시킨다.
 * store 생성·갱신(freeze 규칙 추가, 위반 수정 시): archunit.properties의 allowStoreUpdate를 잠시 true로 두고 이 테스트를 한 번 실행한 뒤 되돌려 store와 함께 커밋한다.
 */
@AnalyzeClasses(packages = "fruition", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureRulesTest {

	/** controller 패키지에 있거나 @Controller/@RestController가 붙은 클래스(평평한 패키지의 컨트롤러 포함). */
	private static final DescribedPredicate<JavaClass> CONTROLLER = resideInAPackage("..controller..")
			.or(metaAnnotatedWith(Controller.class))
			.as("controllers");

	@ArchTest
	static final ArchRule service와_repository는_controller에_의존하지_않는다 = noClasses()
			.that().resideInAnyPackage("..service..", "..repository..")
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

	@ArchTest
	static final ArchRule controller는_repository를_직접_쓰지_않는다 = freeze(noClasses()
			.that(CONTROLLER)
			.should().dependOnClassesThat().resideInAPackage("..repository.."));

	@ArchTest
	static final ArchRule domain은_dto에_의존하지_않는다 = freeze(noClasses()
			.that().resideInAPackage("..domain..")
			.should().dependOnClassesThat().resideInAPackage("..dto.."));

	// fruition.core 도메인 간 순환은 freeze하지 않는다: 탐지 상한(100개)에서 잘린 순환마다 의존 목록이 통째로 기록돼 store가 9천 줄을 넘는다.

	@ArchTest
	static final ArchRule Autowired_필드_주입을_쓰지_않는다 = freeze(noFields()
			.should().beAnnotatedWith(Autowired.class));

	private static final Pattern CORE_MODULE = Pattern.compile("^fruition\\.core\\.([^.]+).*$");

	private static String coreModule(String packageName) {
		Matcher m = CORE_MODULE.matcher(packageName);
		return m.matches() ? m.group(1) : null;
	}

	@ArchTest
	static final ArchRule 다른_도메인의_repository를_직접_쓰지_않는다 = freeze(classes()
			.that().resideInAPackage("fruition.core..")
			.should(ArchConditions.onlyHaveDependenciesWhere(DescribedPredicate.describe(
					"대상이 다른 fruition.core 모듈의 repository가 아니다",
					(Dependency d) -> {
						String target = d.getTargetClass().getPackageName();
						String targetModule = coreModule(target);
						return targetModule == null
								|| !target.startsWith("fruition.core." + targetModule + ".repository")
								|| targetModule.equals(coreModule(d.getOriginClass().getPackageName()));
					}))));

}
