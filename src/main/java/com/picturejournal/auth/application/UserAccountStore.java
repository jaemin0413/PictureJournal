package com.picturejournal.auth.application;

import com.picturejournal.auth.domain.UserAccount;
import java.util.Optional;
import java.util.UUID;

public interface UserAccountStore {

    UserAccount save(UserAccount userAccount);

    Optional<UserAccount> findById(UUID userId);

    Optional<UserAccount> findByEmail(String email);
}
