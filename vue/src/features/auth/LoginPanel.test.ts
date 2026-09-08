import { describe, expect, it } from "vitest";
import source from "./LoginPanel.vue?raw";

describe("LoginPanel", () => {
  it("offers distinct administrator and user demo entry choices with visible form feedback", () => {
    expect(source).toContain('class="role-switch"');
    expect(source).toContain("管理员登录");
    expect(source).toContain("普通用户登录");
    expect(source).toContain("演示账号：admin / admin");
    expect(source).toContain('aria-live="polite"');
  });
});
