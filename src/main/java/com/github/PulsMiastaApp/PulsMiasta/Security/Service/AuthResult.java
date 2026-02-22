package com.github.PulsMiastaApp.PulsMiasta.Security.Service;

/**
 * Carries tokens produced after a successful login or register.
 *
 * @param sessionToken    short-lived sliding session token (15 min, reset on every request)
 * @param rememberMeToken long-lived remember-me token (30/90 days), null when rememberMe=false
 */
public record AuthResult(String sessionToken, String rememberMeToken) {}
