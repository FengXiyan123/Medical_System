import { describe, expect, it } from "vitest";

import { createAuthState } from "./session";

describe("auth state", () => {
  it("persists a successful login and clears all credentials on logout", () => {
    const storage = new Map<string, string>();
    const state = createAuthState({
      getItem: (key) => storage.get(key) ?? null,
      setItem: (key, value) => storage.set(key, value),
      removeItem: (key) => storage.delete(key)
    });

    state.setSession({
      accessToken: "access",
      refreshToken: "refresh",
      user: { id: "u-1", username: "learner", role: "USER" }
    });

    expect(state.isAuthenticated.value).toBe(true);
    expect(state.accessToken.value).toBe("access");
    expect(storage.get("medical-agent.auth")).toContain("learner");

    state.logout();

    expect(state.isAuthenticated.value).toBe(false);
    expect(storage.has("medical-agent.auth")).toBe(false);
  });
});
