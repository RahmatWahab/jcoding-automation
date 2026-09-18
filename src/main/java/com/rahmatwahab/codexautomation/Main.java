package com.rahmatwahab.codexautomation;

/**
 * Process entry point. Delegates to {@link Controller#run(String[])} and
 * propagates its exit code as the process result
 * ({@code raise SystemExit(main())}).
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        System.exit(Controller.run(args));
    }
}
