# OpenFilePower

Natywny menedżer plików dla Androida — wiele folderów, jedna przestrzeń. Kotlin, Jetpack Compose i Material 3, bez reklam i telemetrii.

## Instalacja

Pobierz `OpenFilePower.apk` z [najnowszego wydania](https://github.com/BlackSingularities/OpenFilePower/releases/latest), otwórz APK i zaakceptuj instalację w Androidzie. Wymagany Android 8.0 lub nowszy.

## Możliwości

| Obszar | Funkcje |
| --- | --- |
| Przestrzeń robocza | Niezależne karty folderów, zapisywanie kart, historia, okruszki ścieżki |
| Nawigacja | Rozwijane drzewo, ulubione, wiele źródeł, nowa karta przez przytrzymanie pozycji drzewa |
| Pliki | Kopiuj, wytnij, wklej, wielokrotny wybór, nowe foldery, zmiana nazw, trwałe usuwanie z potwierdzeniem |
| Przeciąganie | Przytrzymaj ikonę pliku; upuść na folder, kartę lub drzewo, aby skopiować |
| Sieć | NAS przez SMB 2/3, logowanie użytkownik/hasło/domena, kopiowanie lokalnie ↔ NAS |
| Widok | Lista i siatka, filtrowanie nazw w bieżącym folderze, sortowanie, ukryte pliki |
| Zdjęcia | Lokalny podgląd, EXIF: aparat, obiektyw, ISO, czas, przysłona, GPS (jeśli zapisano) |
| Tablet | Stały lewy panel od 720 dp i prawy inspektor od 1000 dp; na telefonie panele wysuwane |
| Aktualizacje | Automatyczne sprawdzanie przy uruchomieniu, pobieranie APK, SHA-256, kontrola pakietu i podpisu |

## Pierwsze uruchomienie

1. Wybierz **Wybierz pierwszy folder**. W systemowym oknie udziel dostępu do konkretnego folderu. Możesz dodać kilka folderów, także z karty SD lub USB, jeżeli udostępnia je system.
2. Dla NAS wybierz **Połącz z NAS**. Podaj host/IP, nazwę udziału (bez ścieżki), login i hasło. Serwer musi być osiągalny z sieci telefonu, np. przez Wi-Fi lub VPN.
3. Otwórz nową kartę przyciskiem `+`. Zaznacz pliki checkboxami, wybierz Kopiuj/Wytnij, przejdź do celu i wybierz Wklej.
4. Ikona ⓘ pokazuje właściwości. Na mniejszych ekranach otwórz panel przyciskiem ⓘ w górnym pasku.

## Bezpieczeństwo danych i ograniczenia v0.1

- Aplikacja ma dostęp tylko do folderów wybranych przez użytkownika. Ograniczenia Androida dotyczące katalogów głównych, `Android/data` i `Android/obb` nadal obowiązują.
- Hasła SMB są szyfrowane AES-GCM kluczem Android Keystore. Backup danych aplikacji jest wyłączony. SMB1 nie jest włączany. SMB działa w zaufanej sieci lub VPN; nie wystawiaj serwera bezpośrednio do Internetu.
- Konflikt nazw zatrzymuje kopiowanie zamiast nadpisywać dane. Przy przenoszeniu źródło usuwane jest dopiero po skopiowaniu elementu. Wieloelementowe operacje nie są transakcją: wcześniej ukończone elementy pozostają w celu po błędzie/anulowaniu.
- Transfery działają w procesie aplikacji; trzymaj ją otwartą podczas dużych operacji. Nie ma jeszcze usługi transferów w tle ani wznawiania po zabiciu procesu.
- Anulowanie dotyczy transferów; usuń/zmień nazwę nie są odwracalne. Nie ma kosza. Automatyczne scalanie folderów i nadpisywanie nie są obsługiwane.
- Nie dodawaj tego samego fizycznego folderu pod różnymi źródłami. Wykrywanie kopiowania do własnego podfolderu operuje na ścieżkach źródeł; aliasy serwerów, dowiązania i nakładające się uprawnienia SAF wymagają ostrożności.
- Obsługiwany protokół sieciowy: SMB. SFTP, FTP, WebDAV i automatyczne wykrywanie NAS nie są jeszcze zaimplementowane.
- Podgląd w panelu dotyczy zdjęć lokalnych; EXIF można odczytywać też z NAS. Pliki sieciowe otwierane w innych aplikacjach są najpierw kopiowane do prywatnego cache. Nie ma edytora EXIF, archiwizatora ani rekursywnego indeksowania wyszukiwania.
- Aktualizator sprawdza przy zimnym uruchomieniu aplikacji (można wyłączyć w ustawieniach). Pobranie i instalacja wymagają działania użytkownika; Android nie zezwala zwykłym aplikacjom na ciche instalowanie aktualizacji.

## Budowanie

JDK 17, Android SDK 35, Gradle Wrapper 8.11.1:

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Na Windows użyj `gradlew.bat`. W Android Studio otwórz katalog repozytorium. `local.properties` z `sdk.dir` pozostaje lokalny.

## Wydania i podpis

GitHub Actions sprawdza testy i lint, buduje debug APK przy push/PR. Tag `vX.Y.Z` uruchamia dodatkowo podpisany build release i publikuje APK z sumą SHA-256. Przed tagiem zmień **versionName i versionCode** w `app/build.gradle.kts`; versionCode musi rosnąć. Wydanie musi zawierać pliki o nazwach `OpenFilePower.apk` i `OpenFilePower.apk.sha256`.

Wymagane sekrety Actions: `SIGNING_KEY` (keystore Base64), `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_PASSWORD`. Alias klucza: `openfilepower`. Klucz podpisujący należy bezpiecznie zachować — jego utrata uniemożliwi instalowanie aktualizacji na istniejących instalacjach. Prywatne klucze nie są częścią repozytorium.

## Weryfikacja urządzeniowa

Zobacz [scenariusze QA](docs/QA.md). Build i testy JVM nie zastępują sprawdzenia realnych dostawców SAF, urządzeń NAS i gestów na tablecie.

## Licencja

MIT. Zależności zachowują własne licencje (m.in. jcifs-ng: LGPL-2.1). Zobacz [informacje o komponentach](NOTICE.md).
