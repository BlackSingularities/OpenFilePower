# OpenFilePower

Kombajn do plików, zdjęć i wideo na Androida – cała pamięć urządzenia, karty SD, foldery systemowe i NAS w jednej przestrzeni z kartami, galeria z obsługą RAW, histogramem i trybem sesji na żywo. Kotlin, Jetpack Compose, czarno-biały interfejs wspólny z [OpenPDF](https://github.com/BlackSingularities/OpenPDF). Bez reklam i telemetrii.

[![Android](https://github.com/BlackSingularities/OpenFilePower/actions/workflows/android.yml/badge.svg)](https://github.com/BlackSingularities/OpenFilePower/actions/workflows/android.yml)

## Instalacja

Pobierz **[OpenFilePower.apk](https://github.com/BlackSingularities/OpenFilePower/releases/latest/download/OpenFilePower.apk)** z najnowszego wydania i zainstaluj (Android 8.0+). Przy pierwszym uruchomieniu nadaj **pełny dostęp do plików** – aplikacja od razu otworzy katalog główny pamięci. Kolejne wersje instalują się z poziomu aplikacji.

## Możliwości

| Obszar | Funkcje |
| --- | --- |
| Pamięć | Pełny dostęp do pamięci wewnętrznej i kart SD, zajętość nośników, foldery główne, foldery SAF (USB, chmura), NAS SMB 2/3 |
| Przestrzeń robocza | Niezależne karty folderów, historia, okruszki ścieżki, drzewo folderów w panelu bocznym, ulubione |
| Pliki | Kopiuj, wytnij, wklej, przeciągnij i upuść, zmiana nazwy, nowy folder i plik, wielokrotny wybór, odwracanie zaznaczenia |
| Bezpieczeństwo danych | Kosz z przywracaniem, pytanie o konflikty nazw (zachowaj oba / pomiń / zastąp), ochrona przed kopiowaniem folderu do siebie |
| Archiwa | Pakowanie do ZIP, podgląd i rozpakowywanie (z ochroną przed złośliwymi ścieżkami) |
| Wyszukiwanie | Filtr bieżącego folderu, wyszukiwanie w podfolderach z `*`, kategorie: obrazy, wideo, muzyka, dokumenty, archiwa, APK, pobrane, duże pliki |
| Podgląd | Przeglądarka zdjęć z powiększaniem, edytor tekstu, miniatury zdjęć i filmów, właściwości z EXIF, rozmiarem folderu, SHA-256 i MD5 |
| Transfery | Postęp w procentach i prędkość, anulowanie, praca w tle z powiadomieniem, natychmiastowe przenoszenie na tym samym nośniku |
| Galeria i foto | Oś czasu i albumy całej pamięci, filtry Zdjęcia/RAW/Wideo, RAW (ARW, NEF, CR2, CR3, RAF, DNG, RW2, ORF, PEF…) z podglądem z osadzonego JPEG, histogram RGB, maska przepaleń, plakietki EXIF, pokaz slajdów, odtwarzanie filmów |
| Tryb sesji | Folder obserwowany na żywo (także NAS): nowe zdjęcia z aparatu lub karty Wi-Fi pojawiają się od razu i mogą otwierać się automatycznie |
| Synchronizacja | Pary folderów telefon ↔ NAS/SD/USB, dwu- lub jednokierunkowo, konflikty z wyborem wersji, praca offline z licznikiem oczekujących zmian, automatycznie po połączeniu i w tle, podgląd i historia |
| Sieć | Automatyczne wykrywanie NAS (mDNS + skan SMB), lista udziałów, logowanie jako gość lub użytkownik |
| Narzędzia | Masowa zmiana nazw z podglądem, analiza zajętego miejsca, skróty folderów na ekranie głównym, widok kompaktowy, rozmiar siatki S/M/L |
| Tablet | Stały panel boczny od 720 dp, inspektor właściwości od 1000 dp; na telefonie szuflada i panele |
| Aktualizacje | Sprawdzanie przy starcie, pobieranie z GitHub Releases, kontrola SHA-256, pakietu, wersji i certyfikatu podpisu |

## Dostęp do plików i ograniczenia Androida

- Pełny dostęp to systemowe uprawnienie „Dostęp do wszystkich plików” (Android 11+) albo uprawnienia pamięci (Android 8–10). Bez niego można pracować na folderach wybranych przez system i na NAS.
- Od Androida 11 system blokuje wszystkim menedżerom plików bez roota bezpośredni dostęp do `Android/data` i `Android/obb`. Na Androidzie 11–12 aplikacja oferuje dostęp przez okno systemowe (Ustawienia → „Dostęp do Android/data”); na 13+ nie jest to możliwe.
- Kosz działa w pamięci wewnętrznej (ukryty folder `.OpenFilePowerKosz`); w folderach SAF i na NAS usuwanie jest trwałe – aplikacja o tym informuje.
- Hasła SMB są szyfrowane AES-GCM kluczem Android Keystore; SMB1 jest wyłączony. Używaj SMB w zaufanej sieci lub przez VPN.
- Wieloelementowe operacje nie są transakcją: po anulowaniu ukończone elementy pozostają w celu.

## Budowanie

JDK 17+, Android SDK 36, Gradle Wrapper 8.14:

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Wersja debug ma pakiet `pl.openfilepower.debug`, więc instaluje się obok wydania i nie powoduje konfliktu podpisów.

## Wydania i podpis

Każdy push na `main` buduje podpisany APK i publikuje wydanie: wersja z pliku `VERSION`, a gdy taki tag już istnieje – z numerem kompilacji (np. `0.2.0.57`). Notatki pochodzą z `CHANGELOG.md`. Commit z `[skip release]` nie tworzy wydania.

CI sprawdza, czy APK jest podpisany certyfikatem o SHA-256 `470753b8716af4f6d59539aadacc0d9eadb17eabca4de18f6524a5ff2ae2fddb` (wartość `releaseCertSha256` w `app/build.gradle.kts`) – inne podpisanie przerywa wydanie, bo zainstalowane kopie nie mogłyby się zaktualizować. Sekrety Actions: `SIGNING_KEY` (keystore Base64), `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_PASSWORD`, alias `openfilepower`. Lokalnie `assembleRelease` używa `.secrets/release.jks` (poza repozytorium). Utrata klucza uniemożliwi aktualizacje istniejących instalacji – przechowuj kopię zapasową.

Jeśli Android zgłasza konflikt podpisu przy instalacji, na telefonie jest kopia podpisana innym kluczem (np. lokalna kompilacja sprzed 0.2). Odinstaluj ją raz i zainstaluj APK z wydania.

## Licencja

MIT. Zależności zachowują własne licencje (m.in. jcifs-ng: LGPL-2.1). Zobacz [informacje o komponentach](NOTICE.md) i [scenariusze weryfikacji](docs/QA.md).
