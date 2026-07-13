package com.picturejournal.auth.application;

import java.util.Optional;

public interface AuthSessionStore {

    AuthSession save(AuthSession authSession);
    void deleteByToken(String token);

    Optional<AuthSession> findByToken(String token);
}
