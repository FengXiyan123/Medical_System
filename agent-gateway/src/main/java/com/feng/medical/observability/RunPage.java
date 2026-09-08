package com.feng.medical.observability;

import java.util.List;
public record RunPage(List<AdminRun> items, String nextCursor) { }
