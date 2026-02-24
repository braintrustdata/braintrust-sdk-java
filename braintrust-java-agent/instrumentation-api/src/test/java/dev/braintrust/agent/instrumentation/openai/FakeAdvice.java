package dev.braintrust.agent.instrumentation.openai;

import net.bytebuddy.asm.Advice;

/**
 * Test advice that references FakeLibraryClass in various ways so that ReferenceCreator
 * can extract those references. This exercises:
 * <ul>
 *   <li>Instance method call (invokevirtual) — doWork(String)</li>
 *   <li>Static field access (getstatic) — VERSION</li>
 *   <li>Return type extraction — createResult() returns FakeResult</li>
 *   <li>BFS transitive discovery — calls FakeHelper.record() which calls FakeLibraryClass.parse()</li>
 * </ul>
 */
public class FakeAdvice {

    @Advice.OnMethodEnter
    public static void onEnter(@Advice.Argument(0) String arg) {
        FakeLibraryClass lib = new FakeLibraryClass();

        // Instance method call → reference to doWork(String)String
        String result = lib.doWork(arg);

        // Static field access → reference to VERSION field
        String version = FakeLibraryClass.VERSION;

        // Return type → reference to FakeResult
        FakeResult res = lib.createResult();

        // Transitive: FakeHelper is in the instrumentation package, so BFS will follow into it
        // and discover its reference to FakeLibraryClass.parse()
        FakeHelper.record(arg);
    }
}
