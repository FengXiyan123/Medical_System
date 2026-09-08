import { computed, ref } from "vue";

export type UserRole = "USER" | "ADMIN";

export interface AuthSession {
  accessToken: string;
  refreshToken: string;
  user: {
    id: string;
    username: string;
    role: UserRole;
  };
}

export interface StoragePort {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
  removeItem(key: string): void;
}

const STORAGE_KEY = "medical-agent.auth";

export function createAuthState(storage: StoragePort) {
  const initial = readSession(storage);
  const session = ref<AuthSession | null>(initial);
  const accessToken = computed(() => session.value?.accessToken ?? null);
  const isAuthenticated = computed(() => session.value !== null);

  function setSession(next: AuthSession) {
    session.value = next;
    storage.setItem(STORAGE_KEY, JSON.stringify(next));
  }

  function logout() {
    session.value = null;
    storage.removeItem(STORAGE_KEY);
  }

  return { session, accessToken, isAuthenticated, setSession, logout };
}

function readSession(storage: StoragePort): AuthSession | null {
  const value = storage.getItem(STORAGE_KEY);
  if (!value) return null;
  try {
    const parsed = JSON.parse(value) as AuthSession;
    if (!parsed.accessToken || !parsed.refreshToken || !parsed.user?.id || !parsed.user?.username) return null;
    return parsed;
  } catch {
    storage.removeItem(STORAGE_KEY);
    return null;
  }
}
