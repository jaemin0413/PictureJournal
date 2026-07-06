package com.picturejournal.auth.application;

import java.util.Optional;

public interface AuthSessionStore {

    AuthSession save(AuthSession authSession);

    Optional<AuthSession> findByToken(String token);
}
