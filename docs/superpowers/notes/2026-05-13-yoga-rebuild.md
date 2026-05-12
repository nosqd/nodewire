# Vendoring AE2/yoga for Java 17

The upstream `org.appliedenergistics.yoga:yoga:1.0.0` on Maven Central is compiled to Java 21 bytecode (`JavaLanguageVersion.of(21)` in `settings.gradle`). Forge 1.20.1 / MC 1.20.1 runs on Java 17 — Gradle refuses to put a Java-21 jar on a Java-17 runtimeClasspath.

The source itself is Java-17-compatible (only records, which are stable since Java 16). So we rebuild the same source with a patched toolchain and vendor the resulting jar.

## Rebuild steps

```bash
mkdir -p /tmp/yoga && cd /tmp/yoga
curl -sL https://github.com/AppliedEnergistics/yoga/archive/refs/tags/v1.0.0.tar.gz -o yoga.tgz
tar xzf yoga.tgz
cd yoga-1.0.0  # (or yoga-main if you grabbed master)
sed -i 's/JavaLanguageVersion.of(21)/JavaLanguageVersion.of(17)/' settings.gradle
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :jar
# Output: build/libs/yoga-1.0.0.jar (or yoga-0.0.0-SNAPSHOT.jar if no TAG env var)
```

Verify it's Java 17 bytecode (major version 61):
```bash
javap -v -cp build/libs/yoga-*.jar org.appliedenergistics.yoga.YogaNode | grep "major version"
```

Copy into the mod:
```bash
cp build/libs/yoga-*.jar /path/to/nodewire/libs/yoga-1.0.0-j17.jar
```

The mod's `build.gradle.kts` references it via `implementation(files("libs/yoga-1.0.0-j17.jar"))`.

## When to bump

If we update to Yoga 1.x.y or apply patches, repeat the above with the new tag. Filename suffix `-j17` makes it explicit that this jar isn't the upstream artifact, so nobody assumes you can just bump the version coord on Maven Central.

## Upstream PR opportunity

Upstream AE2/yoga doesn't need Java 21 — switching to `JavaLanguageVersion.of(17)` + `tasks.withType<JavaCompile> { options.release.set(17) }` would make it work on Forge 1.20.x without rebuild. Worth filing.
