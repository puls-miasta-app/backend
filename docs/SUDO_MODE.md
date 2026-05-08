# Sudo Mode — dokumentacja

Sudo mode to mechanizm chwilowego podwyższenia uprawnień sesji, wymagany przed wykonaniem **krytycznych operacji na koncie** (zmiana metod 2FA, dodanie/usunięcie passkey, zmiana domyślnej metody weryfikacji). Wzorowany na `sudo` z systemów Unix.

---

## Spis treści

1. [Po co sudo mode?](#po-co-sudo-mode)
2. [Jak działa — przegląd](#jak-działa--przegląd)
3. [Auto-aktywacja po logowaniu](#auto-aktywacja-po-logowaniu)
4. [Czas trwania i wygasanie](#czas-trwania-i-wygasanie)
5. [Metody weryfikacji sudo](#metody-weryfikacji-sudo)
6. [Chronione operacje](#chronione-operacje)
7. [Wymagania dla administratorów](#wymagania-dla-administratorów)
8. [Remember-me a sudo mode](#remember-me-a-sudo-mode)
9. [Przepływy krok po kroku](#przepływy-krok-po-kroku)
10. [Konfiguracja](#konfiguracja)
11. [Odpowiedzi błędów](#odpowiedzi-błędów)
12. [Implementacja techniczna](#implementacja-techniczna)
13. [Endpointy API](#endpointy-api)

---

## Po co sudo mode?

Bez sudo mode jedyną barierą przed zmianą krytycznych ustawień bezpieczeństwa konta jest posiadanie aktywnej sesji (ciasteczko `auth_token`). Oznacza to, że atakujący, który:

- ukradł sesję (np. XSS, nieoczyszczony cookie),
- ma dostęp do urządzenia użytkownika z zalogowaną sesją,

...mógłby dodać własne urządzenie TOTP/passkey do konta i przejąć je trwale.

Sudo mode wymaga **ponownej weryfikacji tożsamości** tuż przed wykonaniem takiej operacji, nawet jeśli użytkownik jest już zalogowany. Dzięki temu kradzież samego ciasteczka sesji nie wystarczy do trwałego przejęcia konta.

---

## Jak działa — przegląd

```
Użytkownik zalogowany (sesja aktywna)
         │
         ▼
Chce wykonać akcję krytyczną (np. dodać TOTP)
         │
         ▼
Czy sudo mode jest aktywny?
   ├─ TAK → akcja dozwolona
   └─ NIE → 403 Forbidden "Sudo mode required"
              │
              ▼
         Użytkownik wybiera metodę weryfikacji
         (TOTP / EMAIL_OTP / PASSKEY)
              │
              ▼
         Weryfikacja przeszła
              │
              ▼
         Sudo mode aktywny przez N minut
              │
              ▼
         Akcja krytyczna dozwolona
              │
              ▼
         Po N minutach sudo wygasa
         (sesja POZOSTAJE aktywna)
```

---

## Auto-aktywacja po logowaniu

Po **każdym udanym logowaniu** sudo mode jest automatycznie aktywowany na skonfigurowany czas:

| Ścieżka logowania            | Endpoint kończący               | Sudo aktywowany? |
|------------------------------|---------------------------------|------------------|
| Hasło (bez 2FA)              | `POST /v1/auth/login`           | ✅ od razu        |
| Hasło + TOTP                 | `POST /v1/auth/login/totp`      | ✅                |
| Hasło + Email OTP            | `POST /v1/auth/login/otp/verify`| ✅                |
| Hasło + Passkey (2FA step 2) | `POST /v1/auth/login/passkey/finish` | ✅           |
| Passkey bezpośrednie         | `POST /v1/auth/passkey/authentication/finish` | ✅  |
| Remember-me przywraca sesję  | (automatycznie w filtrze)       | ❌ NIE            |

Dzięki temu zaraz po zalogowaniu użytkownik może skonfigurować 2FA lub zmienić metody bez dodatkowego kroku weryfikacji — sudo jest już aktywne.

---

## Czas trwania i wygasanie

- Sudo mode wygasa po **15 minutach** od aktywacji (domyślnie).
- Po wygaśnięciu **sesja pozostaje aktywna** — użytkownik jest nadal zalogowany.
- Sudo nie jest przesuwane automatycznie — każda aktywacja ustawia nowy TTL od zera.
- Można je ręcznie dezaktywować przez `POST /v1/auth/sudo/deactivate`.

```
Czas:  0        5min      10min     15min     20min     30min
       │────────│─────────│─────────│─────────│─────────│
       ▲        ▲                   ▲          
       │        │                   │
    Login    Operacja            Sudo wygasło
    (sudo    krytyczna           (sesja nadal
    aktywny) dozwolona           aktywna)
```

---

## Metody weryfikacji sudo

Dostępne metody sudo są zwracane przez `GET /v1/auth/sudo/available-methods`. Lista jest uporządkowana — silniejsze metody na początku:

| Kolejność | Metoda       | Warunek dostępności                      |
|-----------|--------------|------------------------------------------|
| 1         | `TOTP`       | `totpEnabled = true` na koncie           |
| 2         | `PASSKEY`    | Co najmniej jeden passkey zarejestrowany |
| 3         | `EMAIL_OTP`  | Zawsze dostępna (domyślny fallback)      |

### Email OTP jako domyślna metoda

**Email OTP jest zawsze dostępny** — nie wymaga żadnej wcześniejszej konfiguracji. Jest to domyślna metoda sudo dla nowych użytkowników i administratorów do czasu skonfigurowania TOTP lub passkey.

Kod OTP jest ważny 10 minut, maksymalnie 3 próby weryfikacji, cooldown 60 sekund między wysłaniami.

### TOTP

Wymaga wcześniejszego skonfigurowania aplikacji authenticator (np. Google Authenticator, Authy). Kod 6-cyfrowy, ważność ±30 sekund z ochroną przed replay atakiem.

### Passkey / WebAuthn

Wymaga zarejestrowanego passkey (Face ID, Touch ID, Windows Hello, klucz sprzętowy). Weryfikacja przez protokół WebAuthn (challenge-response).

---

## Chronione operacje

Następujące operacje wymagają aktywnego sudo mode (adnotacja `@RequireSudoMode`):

| Operacja                          | Endpoint                               |
|-----------------------------------|----------------------------------------|
| Konfiguracja TOTP (start)         | `POST /v1/auth/totp/setup`             |
| Konfiguracja TOTP (potwierdzenie) | `POST /v1/auth/totp/confirm`           |
| Wyłączenie TOTP                   | `DELETE /v1/auth/totp`                 |
| Włączenie Email OTP               | `POST /v1/auth/email-otp/enable`       |
| Wyłączenie Email OTP              | `DELETE /v1/auth/email-otp`            |
| Rejestracja passkey (start)       | `POST /v1/auth/passkey/registration/begin`   |
| Rejestracja passkey (zapis)       | `POST /v1/auth/passkey/registration/finish`  |
| Usunięcie passkey                 | `DELETE /v1/auth/passkey/credentials/{id}`   |
| Zmiana domyślnej metody 2FA       | `PUT /v1/auth/2fa/default`             |

---

## Wymagania dla administratorów

Każdy użytkownik z rolą administracyjną (`ADMIN_MIASTA`, `ADMIN_GMINY`, `ADMIN_POWIATU`, `ADMIN_WOJEWODZTWA`, `SUPER_ADMIN`) **musi posiadać skonfigurowaną co najmniej jedną metodę 2FA**.

Przy logowaniu bez skonfigurowanej 2FA flaga `mustSetup2FA: true` w odpowiedzi sygnalizuje frontendowi konieczność przekierowania do konfiguracji. Skonfigurowanie 2FA wymaga sudo mode, które jest automatycznie aktywne bezpośrednio po logowaniu.

Email OTP jest dostępny od razu bez konfiguracji i służy jako domyślna metoda sudo do czasu skonfigurowania TOTP lub passkey.

---

## Remember-me a sudo mode

Remember-me i sudo mode to **niezależne mechanizmy**:

| Mechanizm   | Cel                                   | TTL               |
|-------------|---------------------------------------|-------------------|
| Sesja       | Weryfikacja tożsamości                | 15 min (sliding)  |
| Remember-me | Automatyczne odnawianie sesji         | 30 dni (web)      |
| Sudo mode   | Podwyższone uprawnienia do akcji      | 15 min (fixed)    |

Gdy remember-me odtworzy sesję po wygaśnięciu, **sudo NIE jest automatycznie aktywowane**. Jest to celowe zachowanie:

- Odtworzenie sesji z remember-me **nie wymaga** 2FA — to prawidłowe działanie (sesja jest ponownie nawiązywana bez interakcji użytkownika).
- Sudo mode wymaga **świadomej weryfikacji** tuż przed krytyczną operacją.
- Użytkownik wróci do aplikacji zalogowany (sesja aktywna), ale bez sudo. Dopiero gdy będzie chciał zmienić ustawienia konta, zostanie poproszony o weryfikację sudo.

```
Sesja wygasa → Remember-me odtwarza sesję:
  ✅ Użytkownik jest zalogowany
  ✅ Może czytać dane, przeglądać aplikację
  ❌ Sudo mode nieaktywny (wymagana ręczna weryfikacja)
  ❌ Akcje krytyczne zablokowane do czasu aktywacji sudo
```

---

## Przepływy krok po kroku

### 1. Normalne logowanie → sudo aktywne od razu

```
POST /v1/auth/login
  { "email": "...", "password": "...", "rememberMe": true }

→ 200 OK + Set-Cookie: auth_token=<token>
  {
    "success": true,
    "data": { "mustChangePassword": false, "mustSetup2FA": false }
  }

Sudo mode aktywny przez 15 minut od tego momentu.
```

### 2. Logowanie z TOTP → sudo aktywne po kroku 2

```
POST /v1/auth/login
→ 202 Accepted { "pendingToken": "abc", "availableMethods": ["TOTP"] }

POST /v1/auth/login/totp
  { "pendingToken": "abc", "totpCode": "123456" }
→ 200 OK + Set-Cookie: auth_token=<token>

Sudo mode aktywny przez 15 minut od tego momentu.
```

### 3. Sudo wygasło → ponowna aktywacja przez Email OTP

```
GET /v1/auth/sudo/status
→ { "isActive": false, "remainingSeconds": 0 }

GET /v1/auth/sudo/available-methods
→ { "methods": ["TOTP", "EMAIL_OTP"] }  ← TOTP skonfigurowane + zawsze EMAIL_OTP

POST /v1/auth/sudo/otp/send            ← kod wysłany na maila
→ 200 OK "Verification code sent to user@example.com"

POST /v1/auth/sudo/otp/verify
  { "code": "459821" }
→ 200 OK { "message": "Sudo mode activated", "remainingSeconds": 900 }

Teraz można wykonać chronioną operację:
POST /v1/auth/totp/setup
→ 200 OK { "otpAuthUri": "otpauth://..." }
```

### 4. Sudo wygasło → ponowna aktywacja przez TOTP

```
POST /v1/auth/sudo/totp/verify
  { "code": "654321" }
→ 200 OK { "message": "Sudo mode activated", "remainingSeconds": 900 }
```

### 5. Sudo wygasło → ponowna aktywacja przez Passkey

```
POST /v1/auth/sudo/begin
→ 200 OK { "challenge": "...", "sessionKey": "..." }

(przeglądarka wywołuje navigator.credentials.get())

POST /v1/auth/sudo/finish
  { "sessionKey": "...", "id": "...", ... }
→ 200 OK { "message": "Sudo mode activated", "remainingSeconds": 900 }
```

---

## Konfiguracja

```properties
# Czas trwania sudo mode po aktywacji (minuty)
auth.sudo.ttl-minutes=${AUTH_SUDO_TTL_MINUTES:15}

# Email OTP dla sudo — TTL kodu, cooldown, maks. próby
auth.sudo-otp.ttl-minutes=${AUTH_SUDO_OTP_TTL_MINUTES:10}
auth.sudo-otp.cooldown-seconds=${AUTH_SUDO_OTP_COOLDOWN_SECONDS:60}
auth.sudo-otp.max-attempts=${AUTH_SUDO_OTP_MAX_ATTEMPTS:3}
```

---

## Odpowiedzi błędów

| Kod | Znaczenie                                                               |
|-----|-------------------------------------------------------------------------|
| 401 | Brak aktywnej sesji (nieuwierzytelniony)                                |
| 403 | Sudo mode wymagany — sesja aktywna, ale brak aktywnego sudo             |
| 401 | Błędny kod OTP / TOTP                                                   |
| 429 | Wyczerpane próby OTP — poproś o nowy kod                               |
| 400 | Metoda niedostępna (np. TOTP verify gdy TOTP nie jest skonfigurowane)   |

Przykład odpowiedzi 403:
```json
{
  "success": false,
  "message": "Sudo mode required"
}
```

---

## Implementacja techniczna

### Dlaczego HandlerInterceptor zamiast Filter?

Poprzednia implementacja używała `javax.servlet.Filter` w Spring Security filter chain. **To nie działało** — filtr uruchamiał się przed `DispatcherServlet`, który jest odpowiedzialny za resolwację handlera. Atrybut `HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE` był zawsze `null`, więc warunek `handler instanceof HandlerMethod` nigdy nie był spełniony i żadna weryfikacja nie była wykonywana.

Aktualne rozwiązanie: `SudoModeFilter` implementuje `HandlerInterceptor`. Interceptory Spring MVC uruchamiają się **po** resolwacji handlera przez `DispatcherServlet`, ale **przed** wykonaniem metody controllera. W tym momencie informacje o adnotacjach handlera są dostępne.

```
HTTP Request
    │
    ▼
Spring Security Filter Chain
    ├── AuthTokenFilter  ← ustawia SecurityContext (sesja → użytkownik)
    └── inne filtry
    │
    ▼
DispatcherServlet
    ├── HandlerMapping → resolwacja handlera → @RequireSudoMode widoczne
    ├── SudoModeFilter.preHandle()  ← tutaj sprawdzamy sudo!
    └── Wykonanie controllera
```

### Przechowywanie w Redis

```
sudo:{sessionToken} → "true"   TTL: auth.sudo.ttl-minutes
```

Sudo mode jest powiązany z konkretnym tokenem sesji, nie z użytkownikiem. Po wylogowaniu (invalidacja sesji) i zalogowaniu na nowe konto sudo na starym tokenie wygasa naturalnie.

### Bezpieczeństwo

- Sudo mode nie jest przenoszony do nowej sesji przy odtworzeniu z remember-me.
- Każda aktywacja sudo resetuje TTL od zera (nie jest sliding window).
- Wylogowanie aktywnie usuwa wpis sudo z Redis (oprócz wygaśnięcia sesji).

---

## Endpointy API

Pełna dokumentacja w Swagger UI: `GET /api/swagger-ui/index.html`

### Sprawdzenie statusu

```
GET  /v1/auth/sudo/status
     → { isActive: bool, remainingSeconds: int }

GET  /v1/auth/sudo/available-methods
     → { methods: ["TOTP", "PASSKEY", "EMAIL_OTP"] }
```

### Aktywacja

```
# Email OTP (zawsze dostępne)
POST /v1/auth/sudo/otp/send
POST /v1/auth/sudo/otp/verify    body: { code: "123456" }

# TOTP (gdy skonfigurowane)
POST /v1/auth/sudo/totp/verify   body: { code: "123456" }

# Passkey (gdy skonfigurowane)
POST /v1/auth/sudo/begin
POST /v1/auth/sudo/finish        body: { sessionKey, id, rawId, type, response }
```

### Dezaktywacja

```
POST /v1/auth/sudo/deactivate
```

Wszystkie endpointy sudo wymagają aktywnej sesji (cookie `auth_token`).
