import { describe, expect, it } from "vitest";

import { createAdminState } from "./adminState";

describe("administrator workspace state", () => {
  it("exposes the management entrance only to an administrator and resets the selected view", () => {
    const state = createAdminState();

    state.open("ADMIN");
    state.select("USAGE");
    expect(state.visible.value).toBe(true);
    expect(state.activeView.value).toBe("USAGE");

    state.open("USER");
    expect(state.visible.value).toBe(false);
    expect(state.activeView.value).toBe("TRACES");
  });
});
