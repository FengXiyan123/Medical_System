import { describe, expect, it } from "vitest";

import { shouldSubmitOnEnter } from "./composerKeys";

describe("shouldSubmitOnEnter", () => {
  it("submits only an unmodified Enter outside an IME composition", () => {
    expect(shouldSubmitOnEnter({ key: "Enter", shiftKey: false, isComposing: false })).toBe(true);
    expect(shouldSubmitOnEnter({ key: "Enter", shiftKey: true, isComposing: false })).toBe(false);
    expect(shouldSubmitOnEnter({ key: "Enter", shiftKey: false, isComposing: true })).toBe(false);
  });
});
