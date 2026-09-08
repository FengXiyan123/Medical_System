import { describe, expect, it } from "vitest";
import source from "./App.vue?raw";

describe("application shell", () => {
  it("keeps authentication at the application boundary and separates public login from the workspace", () => {
    expect(source).toContain('v-if="!activeSession && !restoringSession"');
    expect(source).toContain('class="app-shell"');
    expect(source).toContain('class="workspace-nav"');
    expect(source).not.toContain('v-if="auth.isAuthenticated"');
  });

  it("lets application workspaces use the available screen width", () => {
    expect(source).not.toContain('max-width:1500px');
    expect(source).toContain('.app-content { width:100%;');
  });
});
