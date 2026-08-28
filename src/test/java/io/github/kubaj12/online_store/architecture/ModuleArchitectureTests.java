package io.github.kubaj12.online_store.architecture;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.MonthDay;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.Year;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Controller;
import org.springframework.stereotype.Repository;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvent;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.library.dependencies.SliceAssignment;
import com.tngtech.archunit.library.dependencies.SliceIdentifier;

import io.github.kubaj12.online_store.OnlineStoreApplication;
import io.github.kubaj12.online_store.shared.auditing.AuditEventRecorder;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.MappedSuperclass;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

@AnalyzeClasses(
		packagesOf = OnlineStoreApplication.class,
		importOptions = ImportOption.DoNotIncludeTests.class
)
public class ModuleArchitectureTests {

	private static final String BASE_PACKAGE = "io.github.kubaj12.online_store";
	private static final String SHARED_PACKAGE = BASE_PACKAGE + ".shared";
	private static final List<String> FEATURE_MODULES = List.of(
			"identityaccess",
			"customers",
			"catalogpricing",
			"inventory",
			"cartordering",
			"notifications"
	);
	private static final Set<String> MODULE_LAYERS = Set.of(
			"web",
			"application",
			"domain",
			"persistence"
	);
	private static final Set<String> AMBIENT_NOW_TYPES = Set.of(
			Instant.class.getName(),
			LocalDate.class.getName(),
			LocalDateTime.class.getName(),
			LocalTime.class.getName(),
			MonthDay.class.getName(),
			OffsetDateTime.class.getName(),
			OffsetTime.class.getName(),
			Year.class.getName(),
			YearMonth.class.getName(),
			ZonedDateTime.class.getName()
	);
	private static final Set<String> SYSTEM_CLOCK_FACTORIES = Set.of(
			"system",
			"systemDefaultZone",
			"systemUTC",
			"tickMillis",
			"tickMinutes",
			"tickSeconds"
	);
	static final ArchCondition<JavaClass> USE_INJECTED_CLOCK = new ArchCondition<>(
			"obtain current time only from the injected Clock"
	) {
		@Override
		public void check(JavaClass javaClass, ConditionEvents events) {
			for (JavaMethodCall methodCall : javaClass.getMethodCallsFromSelf()) {
				if (isAmbientTimeCall(methodCall)) {
					events.add(SimpleConditionEvent.violated(
							methodCall,
							"Ambient time source: " + methodCall.getDescription()
					));
				}
			}
		}
	};

	@ArchTest
	public static final ArchRule BASE_PACKAGE_CONTAINS_ONLY_DECLARED_NAMESPACES = classes()
			.that().resideInAPackage(BASE_PACKAGE + "..")
			.should().resideInAnyPackage(basePackageWhitelist());

	@ArchTest
	public static final ArchRule SHARED_KERNEL_CONTAINS_ONLY_DECLARED_NAMESPACES = classes()
			.that().resideInAPackage(SHARED_PACKAGE + "..")
			.should().resideInAnyPackage(sharedKernelPackagePatterns());

	@ArchTest
	public static final ArchRule SHARED_NAMESPACE_PACKAGES_CONTAIN_ONLY_PACKAGE_INFO = classes()
			.that().resideInAnyPackage(SHARED_PACKAGE, SHARED_PACKAGE + ".web")
			.should().haveSimpleName("package-info");

	@ArchTest
	public static final ArchRule FEATURE_CODE_STAYS_IN_A_DECLARED_LAYER = classes()
			.that().resideInAnyPackage(featureModulePackagePatterns())
			.and().doNotHaveSimpleName("package-info")
			.should(new ArchCondition<JavaClass>("reside in a declared module layer") {
				@Override
				public void check(JavaClass javaClass, ConditionEvents events) {
					if (layerOf(javaClass).isEmpty()) {
						events.add(SimpleConditionEvent.violated(
								javaClass,
								javaClass.getName() + " is outside web, application, domain, or persistence"
						));
					}
				}
			})
			.allowEmptyShould(true);

	@ArchTest
	public static final ArchRule MODULE_LAYERS_FOLLOW_PORTS_AND_ADAPTERS = classes()
			.that().resideInAnyPackage(featureModulePackagePatterns())
			.should(new ArchCondition<JavaClass>("respect the module's ports-and-adapters dependency direction") {
				@Override
				public void check(JavaClass javaClass, ConditionEvents events) {
					Optional<String> originModule = moduleOf(javaClass);
					Optional<String> originLayer = layerOf(javaClass);
					if (originModule.isEmpty() || originLayer.isEmpty()) {
						return;
					}

					for (Dependency dependency : javaClass.getDirectDependenciesFromSelf()) {
						JavaClass targetClass = dependency.getTargetClass();
						if (!originModule.equals(moduleOf(targetClass))) {
							continue;
						}

						Optional<String> targetLayer = layerOf(targetClass);
						if (targetLayer.isEmpty() || !isAllowedLayerDependency(originLayer.get(), targetLayer.get())) {
							events.add(violation(
								dependency,
								"Module layer bypass: " + dependency.getDescription()
							));
						}
					}
				}
			});

	@ArchTest
	public static final ArchRule MODULE_COLLABORATION_USES_APPLICATION_BOUNDARIES = classes()
			.that().resideInAnyPackage(featureModulePackagePatterns())
			.should(new ArchCondition<JavaClass>("collaborate with other modules only application-to-application") {
				@Override
				public void check(JavaClass javaClass, ConditionEvents events) {
					String originModule = moduleOf(javaClass).orElseThrow();

					for (Dependency dependency : javaClass.getDirectDependenciesFromSelf()) {
						JavaClass targetClass = dependency.getTargetClass();
						Optional<String> targetModule = moduleOf(targetClass);
						if (targetModule.isEmpty() || originModule.equals(targetModule.get())) {
							continue;
						}

						if (!layerOf(javaClass).filter("application"::equals).isPresent()
								|| !layerOf(targetClass).filter("application"::equals).isPresent()) {
							events.add(violation(
								dependency,
								"Module boundary bypass: " + dependency.getDescription()
							));
						}
					}
				}
			});

	@ArchTest
	public static final ArchRule DOMAIN_CODE_USES_ONLY_DOMAIN_SAFE_DEPENDENCIES = classes()
			.that().resideInAnyPackage(domainPackagePatterns())
			.should(new ArchCondition<JavaClass>(
					"depend only on JDK types, its own domain, or shared domain primitives"
			) {
				@Override
				public void check(JavaClass javaClass, ConditionEvents events) {
					for (Dependency dependency : javaClass.getDirectDependenciesFromSelf()) {
						if (!isAllowedDomainDependency(javaClass, dependency.getTargetClass())) {
							events.add(violation(
								dependency,
								"Domain dependency violation: " + dependency.getDescription()
							));
						}
					}
				}
			});

	@ArchTest
	public static final ArchRule SHARED_KERNEL_DOES_NOT_DEPEND_ON_FEATURE_MODULES = noClasses()
			.that().resideInAPackage(SHARED_PACKAGE + "..")
			.should().dependOnClassesThat().resideInAnyPackage(featureModulePackagePatterns());

	@ArchTest
	public static final ArchRule ONLY_APPLICATION_SERVICES_RECORD_AUDIT_EVENTS = classes()
			.that().resideInAnyPackage(featureModulePackagePatterns())
			.should(new ArchCondition<JavaClass>("access the audit recorder only from an application layer") {
				@Override
				public void check(JavaClass javaClass, ConditionEvents events) {
					if (layerOf(javaClass).filter("application"::equals).isPresent()) {
						return;
					}

					for (Dependency dependency : javaClass.getDirectDependenciesFromSelf()) {
						if (dependency.getTargetClass().isEquivalentTo(AuditEventRecorder.class)) {
							events.add(violation(
									dependency,
									"Audit boundary bypass: " + dependency.getDescription()
							));
						}
					}
				}
			});

	@ArchTest
	public static final ArchRule PRODUCTION_CODE_USES_THE_INJECTED_CLOCK = classes()
			.that().resideInAnyPackage(featureAndSharedPackagePatterns())
			.and().doNotHaveSimpleName("package-info")
			.should(USE_INJECTED_CLOCK)
			.allowEmptyShould(true);

	@ArchTest
	public static final ArchRule COMPOSITION_ROOT_DEPENDENCIES_ARE_ONE_WAY = noClasses()
			.that().resideInAnyPackage(featureAndSharedPackagePatterns())
			.should().dependOnClassesThat().resideInAPackage(BASE_PACKAGE);

	@ArchTest
	public static final ArchRule FEATURE_MODULES_ARE_FREE_OF_CYCLES = slices()
			.assignedFrom(new FeatureModuleSliceAssignment())
			.should().beFreeOfCycles();

	@ArchTest
	public static final ArchRule MVC_CONTROLLERS_RESIDE_IN_WEB_ADAPTERS = classes()
			.that().areAnnotatedWith(Controller.class)
			.or().areAnnotatedWith(RestController.class)
			.should().resideInAnyPackage(webAdapterPackagePatterns())
			.allowEmptyShould(true);

	@ArchTest
	public static final ArchRule MVC_CONTROLLER_ADVICE_RESIDES_IN_WEB_PACKAGES = classes()
			.that().areAnnotatedWith(ControllerAdvice.class)
			.or().areAnnotatedWith(RestControllerAdvice.class)
			.should().resideInAnyPackage(webPackagePatternsIncludingShared())
			.allowEmptyShould(true);

	@ArchTest
	public static final ArchRule SPRING_REPOSITORIES_RESIDE_IN_PERSISTENCE_ADAPTERS = classes()
			.that().areAnnotatedWith(Repository.class)
			.or().areAssignableTo(org.springframework.data.repository.Repository.class)
			.should().resideInAnyPackage(persistenceAdapterPackagePatterns())
			.allowEmptyShould(true);

	@ArchTest
	public static final ArchRule JPA_MAPPINGS_RESIDE_IN_PERSISTENCE_ADAPTERS = classes()
			.that().areAnnotatedWith(Entity.class)
			.or().areAnnotatedWith(Embeddable.class)
			.or().areAnnotatedWith(MappedSuperclass.class)
			.should().resideInAnyPackage(persistenceAdapterPackagePatterns())
			.allowEmptyShould(true);

	private static Optional<String> moduleOf(JavaClass javaClass) {
		String packageName = javaClass.getPackageName();
		String modulePrefix = BASE_PACKAGE + ".";
		if (!packageName.startsWith(modulePrefix)) {
			return Optional.empty();
		}

		String relativePackage = packageName.substring(modulePrefix.length());
		String candidate = relativePackage.split("\\.", 2)[0];
		return FEATURE_MODULES.contains(candidate) ? Optional.of(candidate) : Optional.empty();
	}

	private static Optional<String> layerOf(JavaClass javaClass) {
		Optional<String> module = moduleOf(javaClass);
		if (module.isEmpty()) {
			return Optional.empty();
		}

		String modulePackage = BASE_PACKAGE + "." + module.get();
		String packageName = javaClass.getPackageName();
		if (!packageName.startsWith(modulePackage + ".")) {
			return Optional.empty();
		}

		String relativePackage = packageName.substring(modulePackage.length() + 1);
		String candidate = relativePackage.split("\\.", 2)[0];
		return MODULE_LAYERS.contains(candidate) ? Optional.of(candidate) : Optional.empty();
	}

	private static boolean isAllowedLayerDependency(String originLayer, String targetLayer) {
		return switch (originLayer) {
			case "web" -> Set.of("web", "application").contains(targetLayer);
			case "application" -> Set.of("application", "domain").contains(targetLayer);
			case "domain" -> "domain".equals(targetLayer);
			case "persistence" -> Set.of("persistence", "application", "domain").contains(targetLayer);
			default -> false;
		};
	}

	private static boolean isAmbientTimeCall(JavaMethodCall methodCall) {
		String ownerName = methodCall.getTargetOwner().getName();
		String methodName = methodCall.getName();

		if (AMBIENT_NOW_TYPES.contains(ownerName) && "now".equals(methodName)) {
			List<JavaClass> parameterTypes = methodCall.getTarget().getRawParameterTypes();
			return parameterTypes.size() != 1
					|| !Clock.class.getName().equals(parameterTypes.getFirst().getName());
		}

		if (System.class.getName().equals(ownerName) && "currentTimeMillis".equals(methodName)) {
			return true;
		}

		return Clock.class.getName().equals(ownerName) && SYSTEM_CLOCK_FACTORIES.contains(methodName);
	}

	private static boolean isAllowedDomainDependency(JavaClass originClass, JavaClass targetClass) {
		String targetPackage = targetClass.getPackageName();
		if (targetPackage.startsWith("java.") && !isPackageOrSubpackage(targetPackage, "java.sql")) {
			return true;
		}

		if (isSharedDomainPrimitivePackage(targetPackage)) {
			return true;
		}

		return moduleOf(originClass).equals(moduleOf(targetClass))
				&& layerOf(targetClass).filter("domain"::equals).isPresent();
	}

	private static boolean isSharedDomainPrimitivePackage(String packageName) {
		return isPackageOrSubpackage(packageName, SHARED_PACKAGE + ".time")
				|| isPackageOrSubpackage(packageName, SHARED_PACKAGE + ".money")
				|| isPackageOrSubpackage(packageName, SHARED_PACKAGE + ".auditing");
	}

	private static boolean isPackageOrSubpackage(String packageName, String expectedPackage) {
		return packageName.equals(expectedPackage) || packageName.startsWith(expectedPackage + ".");
	}

	private static ConditionEvent violation(Dependency dependency, String message) {
		return SimpleConditionEvent.violated(dependency, message);
	}

	private static String[] basePackageWhitelist() {
		String[] featurePackages = featureModulePackagePatterns();
		String[] allowedPackages = new String[featurePackages.length + 2];
		allowedPackages[0] = BASE_PACKAGE;
		allowedPackages[1] = SHARED_PACKAGE + "..";
		System.arraycopy(featurePackages, 0, allowedPackages, 2, featurePackages.length);
		return allowedPackages;
	}

	private static String[] sharedKernelPackagePatterns() {
		return new String[] {
			SHARED_PACKAGE,
			SHARED_PACKAGE + ".time..",
			SHARED_PACKAGE + ".money..",
			SHARED_PACKAGE + ".auditing..",
			SHARED_PACKAGE + ".web",
			SHARED_PACKAGE + ".web.error.."
		};
	}

	private static String[] featureModulePackagePatterns() {
		return FEATURE_MODULES.stream()
				.map(module -> BASE_PACKAGE + "." + module + "..")
				.toArray(String[]::new);
	}

	private static String[] featureAndSharedPackagePatterns() {
		String[] featurePackages = featureModulePackagePatterns();
		String[] featureAndSharedPackages = new String[featurePackages.length + 1];
		System.arraycopy(featurePackages, 0, featureAndSharedPackages, 0, featurePackages.length);
		featureAndSharedPackages[featurePackages.length] = SHARED_PACKAGE + "..";
		return featureAndSharedPackages;
	}

	private static String[] domainPackagePatterns() {
		return FEATURE_MODULES.stream()
				.map(module -> BASE_PACKAGE + "." + module + ".domain..")
				.toArray(String[]::new);
	}

	private static String[] webAdapterPackagePatterns() {
		return FEATURE_MODULES.stream()
				.map(module -> BASE_PACKAGE + "." + module + ".web..")
				.toArray(String[]::new);
	}

	private static String[] webPackagePatternsIncludingShared() {
		String[] featureWebPackages = webAdapterPackagePatterns();
		String[] allWebPackages = new String[featureWebPackages.length + 1];
		System.arraycopy(featureWebPackages, 0, allWebPackages, 0, featureWebPackages.length);
		allWebPackages[featureWebPackages.length] = SHARED_PACKAGE + ".web..";
		return allWebPackages;
	}

	private static String[] persistenceAdapterPackagePatterns() {
		return FEATURE_MODULES.stream()
				.map(module -> BASE_PACKAGE + "." + module + ".persistence..")
				.toArray(String[]::new);
	}

	private static final class FeatureModuleSliceAssignment implements SliceAssignment {

		@Override
		public SliceIdentifier getIdentifierOf(JavaClass javaClass) {
			return moduleOf(javaClass)
					.map(SliceIdentifier::of)
					.orElseGet(SliceIdentifier::ignore);
		}

		@Override
		public String getDescription() {
			return "the six feature modules";
		}
	}
}
