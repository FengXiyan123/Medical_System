import { describe, expect, it } from "vitest";

import { executionModes } from "./modes";

describe("executionModes", () => {
  it("exposes the three approved knowledge-execution modes", () => {
    expect(executionModes.map((mode) => mode.id)).toEqual([
      "AUTO_KB",
      "MANUAL_KB",
      "AGENT"
    ]);
  });
});
