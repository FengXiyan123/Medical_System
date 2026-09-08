import { describe, expect, it, vi } from "vitest";

import { AdminApi } from "./api";

const session = {
  accessToken: "admin-access-token",
  refreshToken: "refresh-token",
  user: { id: "admin-1", username: "admin", role: "ADMIN" as const }
};

describe("AdminApi", () => {
  it("uses the administrator bearer token and retains every trace filter", async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({ items: [] }), {
      status: 200, headers: { "Content-Type": "application/json" }
    }));
    vi.stubGlobal("fetch", fetchMock);
    const api = new AdminApi(session);

    await api.listTraces({ userId: "user 1", mode: "AGENT", status: "FAILED", createdFrom: "2026-09-01", createdTo: "2026-09-07" });

    expect(fetchMock).toHaveBeenCalledWith(
      "/api/admin/runs?userId=user+1&mode=AGENT&status=FAILED&createdFrom=2026-09-01&createdTo=2026-09-07",
      expect.objectContaining({ headers: expect.objectContaining({ Authorization: "Bearer admin-access-token" }) })
    );
  });

  it("never silently treats a failed dashboard response as an empty report", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify({ message: "没有管理员权限" }), {
      status: 403, headers: { "Content-Type": "application/json" }
    })));

    await expect(new AdminApi(session).usage({})).rejects.toThrow("没有管理员权限");
  });

  it("reports the HTTP status when a gateway error has no JSON message", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response("Bad Gateway", {
      status: 502, headers: { "Content-Type": "text/plain" }
    })));

    await expect(new AdminApi(session).listKnowledgeBases()).rejects.toThrow("管理请求失败（HTTP 502）");
  });
});
