import type { AuthSession } from "./session";

interface TokenResponse {
  accessToken: string;
  refreshToken: string;
  user: AuthSession["user"];
}

export class AuthApi {
  constructor(private readonly baseUrl = "/api") {}

  async login(username: string, password: string): Promise<AuthSession> {
    return this.post<TokenResponse>("/auth/login", { username, password });
  }

  async refresh(refreshToken: string): Promise<AuthSession> {
    return this.post<TokenResponse>("/auth/refresh", { refreshToken });
  }

  async logout(refreshToken: string): Promise<void> {
    await this.post<void>("/auth/logout", { refreshToken });
  }

  private async post<T>(path: string, body: object): Promise<T> {
    const response = await fetch(`${this.baseUrl}${path}`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body)
    });
    if (response.status === 204) return undefined as T;
    const payload = (await response.json()) as T & { message?: string };
    if (!response.ok) throw new Error(payload.message ?? "认证请求失败");
    return payload;
  }
}
