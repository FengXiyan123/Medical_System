package com.feng.medical.user;

import java.util.Optional;
import java.util.UUID;

public interface UserAccountRepository {
    Optional<UserAccount> findByUsername(String username);

    Optional<UserAccount> findById(UUID id);
}
