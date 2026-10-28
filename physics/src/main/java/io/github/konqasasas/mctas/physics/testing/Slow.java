package io.github.konqasasas.mctas.physics.testing;

/**
 * JUnit category of tests too slow for every pull request, such as long random soundness runs and
 * searches. {@code ./gradlew test} leaves them out; {@code ./gradlew slowTest} runs only them. CI runs
 * both on pushes to main, nightly, and on pull requests labelled {@code full-tests}.
 *
 * <p>Mark a test with {@code @Category(Slow.class)} when it takes more than a few seconds.
 */
public interface Slow {
}
