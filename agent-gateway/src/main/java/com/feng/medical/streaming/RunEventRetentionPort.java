package com.feng.medical.streaming;

import java.time.Instant;

interface RunEventRetentionPort {
    int deleteBefore(Instant cutoff);
}
