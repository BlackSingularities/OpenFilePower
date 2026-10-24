## 0.3.2 — Transparent application icon

- Use the transparent application mark in launcher, splash and existing branding surfaces.
- Preserve alpha in legacy and adaptive Android icons; refresh themed icon resources.

# Zmiany

## 1.0.0

- Pierwsze stabilne wydanie. Od teraz wersje numerowane semantycznie (X.Y.Z), wydanie powstaje z tagu.
- Płynna gęstość widoku plików szczypaniem – od zwartej listy bez ikon po duże kafelki.
- Tło okna zgodne z motywem jasnym i ciemnym.

## 0.4.0

- **Synchronizacja folderów** telefonu z NAS, kartą SD, USB lub innym folderem: dwukierunkowa albo jednokierunkowa (kopia zapasowa).
  - Porównanie trójstronne ze stanem z ostatniej synchronizacji: wiadomo, która strona coś zmieniła, a usunięcie da się odróżnić od nowego pliku.
  - Konflikty: pytaj, zachowaj obie wersje (`nazwa (konflikt – telefon …)`), nowsza wygrywa, telefon wygrywa albo dysk wygrywa. Konflikt plik ↔ folder nie traci danych. Panel konfliktów pokazuje obie wersje obok siebie.
  - Gdy dysk jest niedostępny, pliki na telefonie działają normalnie, a karta pary pokazuje, ile zmian czeka na wysłanie. Po powrocie do sieci, przy otwarciu aplikacji i w tle co ~15 min zmiany są wysyłane; podsumowanie trafia do powiadomienia i na pulpit.
  - Zapis atomowy (plik tymczasowy → podmiana), sprawdzanie, czy plik nie zmienił się w trakcie, kosz dla plików z telefonu nadpisanych lub usuniętych przez synchronizację, wznawianie przerwanego przebiegu.
  - Zabezpieczenia: synchronizacja zatrzymuje się, gdy folder nagle jest pusty albo miałaby usunąć dużą część plików, i czeka na potwierdzenie.
  - Podgląd zmian przed wykonaniem, historia przebiegów, wzorce pomijanych plików i opcjonalne ukryte pliki.
- Pasek boczny: przytrzymanie folderu otwiera menu (nowa karta, ulubione, odłącz); w ustawieniach można ukryć wybrane kategorie albo wszystkie.
- Przeglądarka: zwarty górny pasek (☰ · wstecz · karty · ⋮), nazwa folderu w linii z liczbą elementów, wyszukiwanie obok przełącznika widoku.
- Podgląd zdjęć: wszystkie opcje pod ⋮, parametry EXIF w nowej karcie z ikonami.

## Niewydane

- Karty na samej górze; nazwa miejsca tylko w karcie – nagłówek pokazuje ścieżkę nadrzędną zamiast powtarzać nazwę.
- Kompaktowy interfejs (domyślnie): niższe nagłówki i mniejsze przyciski.
- Pasek filtrów typów (Wszystko · Foldery · Zdjęcia · Wideo…) włączany w ustawieniach lub menu ⋮; domyślnie ukryty.
- Tryb na żywo zawsze otwiera nowe zdjęcie – usunięto osobną opcję auto-podglądu.
- Uporządkowany pulpit: jedna sekcja „Miejsca” (foldery główne, ulubione, źródła), kategorie jako zwarte pigułki, bez zdublowanych skrótów.
- Galeria: grupowanie po dniach lub miesiącach, zmiana rozmiaru miniatur szczypaniem i przyciskiem, szybsze przewijanie.
- EXIF: pamięć podręczna i wczytywanie z wyprzedzeniem sąsiednich zdjęć w przeglądarce.
- Właściwości otwarte z przeglądarki zdjęć pokazują się nad nią (wcześniej trzeba było ją zamknąć).
- Skróty folderów na ekranie głównym mają własną ikonę z wersją jednokolorową (motywy ikon Androida 13+).

## 0.3.1

- Naprawiono zamykanie się aplikacji po zakończeniu wykrywania serwerów NAS (wątek rozpoznawania nazw mDNS był przerywany w sposób, który zabijał proces).
- Błąd wykrywania NAS pokazuje komunikat zamiast zamykać aplikację; pojedyncze nieudane adresy nie przerywają skanu.
- Pulpit nie wywraca się, gdy indeks multimediów Androida zawiera ten sam plik kilka razy.

## 0.3.0

Menedżer plików połączony z przeglądarką zdjęć i wideo w duchu Fotopodglądu i PhotoManagera.

- **Galeria** całej pamięci: oś czasu dzień po dniu, albumy (foldery z mediami), filtry Zdjęcia / RAW / Wideo, zaznaczanie całych dni.
- **RAW**: ARW, NEF, CR2, CR3, RAF, DNG, RW2, ORF, PEF, SRW i inne – miniatury i podgląd z osadzonego JPEG (z obrotem wg EXIF, buforowane), pełny EXIF.
- **Przeglądarka mediów**: zoom do 8×, odtwarzanie filmów, histogram RGB i jasności, maska przepaleń i niedoświetleń z procentem, plakietki ekspozycji (przysłona, czas, ISO, ogniskowa, korekta), pokaz slajdów, pasek miniatur.
- **Tryb sesji na żywo**: folder sprawdzany co 2 s; nowe zdjęcia z aparatu lub karty Wi-Fi pojawiają się od razu i mogą otwierać się automatycznie (działa też na NAS).
- **Automatyczne wykrywanie NAS** w sieci lokalnej (mDNS/Bonjour + skan portu SMB 445), lista udziałów serwera do wyboru, logowanie jako gość.
- **Kompaktowy ekran startowy z karuzelami**: najnowsze zdjęcia, ostatnie pliki, foldery główne, kategorie, ostatnio odwiedzane foldery, nośniki obok siebie.
- **Widok kompaktowy**, rozmiar siatki S/M/L, filtry typów w folderze (Foldery, Zdjęcia, Wideo, Audio, Dokumenty, Archiwa).
- **Masowa zmiana nazw** z podglądem: wzorzec z numeracją `{n}`, `{nazwa}`, `{data}`, zamiana tekstu, przedrostek/przyrostek, wielkość liter.
- **Analiza zajętego miejsca** folderu z paskami udziału.
- **Skróty folderów na ekranie głównym** telefonu.
- Właściwości: czas trwania, rozdzielczość i przepływność filmów, tagi audio, informacje o pakiecie APK, pełny EXIF (program, lampa, balans bieli, korekta, megapiksele).

## 0.2.0

Pełnoprawny menedżer plików z interfejsem w stylu OpenPDF.

- **Pełny dostęp do pamięci** – cała pamięć wewnętrzna i karty SD bez wybierania folderów (uprawnienie „Dostęp do wszystkich plików”); foldery SAF i NAS nadal dostępne.
- **Ekran startowy** – zajętość pamięci, foldery główne (DCIM, Pobrane, Dokumenty…), kategorie (obrazy, wideo, muzyka, dokumenty, archiwa, APK, duże pliki), ostatnio zmienione pliki, ulubione.
- **Kosz** – usuwanie z możliwością przywrócenia w pamięci wewnętrznej.
- **Konflikty nazw** – zachowaj oba, pomiń albo zastąp (ze scalaniem folderów); natychmiastowe przenoszenie na tym samym nośniku.
- **Archiwa ZIP** – pakowanie, podgląd zawartości i rozpakowywanie (także nazwy z Windows).
- **Wyszukiwanie w podfolderach** z symbolem `*`.
- **Przeglądarka zdjęć** z przesuwaniem i powiększaniem oraz **edytor plików tekstowych**.
- **Transfery w tle** z powiadomieniem, procentem postępu i prędkością.
- **Właściwości** – rozmiar folderów, EXIF, SHA-256 i MD5, kopiowanie ścieżki, „Otwórz za pomocą”.
- Nowy czarno-biały interfejs: okrągłe przyciski, panel ustawień, pasek zaznaczenia, sortowanie naturalne, zapamiętywane ustawienia widoku.
- **Podpis i aktualizacje** – wydania przy każdym pushu na main, kontrola certyfikatu w CI, podpis v2+v3, aktualizator z obsługą rotacji klucza i instalacją przez PackageInstaller. Wersja debug ma osobny pakiet, więc nie koliduje z wydaniem.
