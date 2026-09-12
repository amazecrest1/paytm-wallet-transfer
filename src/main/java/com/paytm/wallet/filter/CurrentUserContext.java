package com.paytm.wallet.filter;

/**
 * Holds the caller's user id (resolved from the bearer token) for the duration of one request.
 * Set by {@link BearerAuthFilter}, read by controllers/services, cleared at the end of the request
 * so a thread returned to the servlet container's pool never leaks a previous request's identity.
 */
public final class CurrentUserContext {

    private static final ThreadLocal<String> USER_ID = new ThreadLocal<>();

    private CurrentUserContext() {
    }

    static void set(String userId) {
        USER_ID.set(userId);
    }

    static void clear() {
        USER_ID.remove();
    }

    public static String userId() {
        String userId = USER_ID.get();
        if (userId == null) {
            throw new IllegalStateException("No authenticated user on this thread — BearerAuthFilter did not run");
        }
        return userId;
    }
}
