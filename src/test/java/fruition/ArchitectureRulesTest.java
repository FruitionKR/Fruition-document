package fruition;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.transaction.annotation.Transactional;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * 지금 코드가 지키는 패키지 의존 규칙을 고정한다. 규칙을 깨야 한다면 이 테스트를 먼저 고친다.
 */
@AnalyzeClasses(packages = "fruition", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureRulesTest {

	@ArchTest
	static final ArchRule service와_repository는_controller에_의존하지_않는다 = noClasses()
			.that().resideInAnyPackage("..service..", "..repository..")
			.should().dependOnClassesThat().resideInAPackage("..controller..");

	@ArchTest
	static final ArchRule domain은_controller와_service에_의존하지_않는다 = noClasses()
			.that().resideInAPackage("..domain..")
			.should().dependOnClassesThat().resideInAnyPackage("..controller..", "..service..");

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
			.that().resideInAPackage("..controller..")
			.should().beAnnotatedWith(Transactional.class)
			.orShould().beAnnotatedWith(jakarta.transaction.Transactional.class);

	@ArchTest
	static final ArchRule controller_메서드에도_Transactional을_두지_않는다 = methods()
			.that().areDeclaredInClassesThat().resideInAPackage("..controller..")
			.should().notBeAnnotatedWith(Transactional.class)
			.andShould().notBeAnnotatedWith(jakarta.transaction.Transactional.class);

}
