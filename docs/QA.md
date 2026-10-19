# Scenariusze weryfikacji na urządzeniu

Do wykonania na telefonie i tablecie; nie stanowią deklaracji wykonanych testów.

- Nadaj dostęp SAF, zabij proces i otwórz ponownie: źródła i karty nadal dostępne.
- Dwa foldery lokalne: skopiuj plik, duży plik oraz drzewo folderów; porównaj SHA-256 źródła i celu.
- Spróbuj wkleić do istniejącej nazwy i własnego podfolderu: oczekiwany błąd bez nadpisania.
- Anuluj duży transfer: źródło pozostaje; niedokończony cel jest usuwany w miarę możliwości dostawcy.
- Przenieś pliki: źródło znika dopiero po zakończeniu kopiowania. Odłącz sieć w połowie transferu.
- SMB: poprawne i błędne hasło, brak dostępu do udziału, katalog tylko do odczytu, polskie znaki w nazwach, lokalnie → NAS, NAS → lokalnie, NAS → NAS.
- Wiele kart: niezależne ścieżki; Wstecz, folder nadrzędny, zamykanie aktywnej/nieaktywnej karty.
- Przeciągnij ikonę na folder, nieaktywną kartę i drzewo. Źródło powinno pozostać.
- Zmień nazwę, utwórz folder, zaznacz wiele elementów, anuluj usuwanie i potwierdź usuwanie testowych plików.
- Sprawdź panel EXIF: zdjęcie z GPS, bez EXIF, uszkodzony plik, duże zdjęcie z NAS.
- Obróć tablet; sprawdź układy 360 / 800 / 1280 dp, duży tekst systemowy i TalkBack.
- Otwórz i udostępnij plik w innej aplikacji.
- Aktualizacja: wyższa wersja, ta sama wersja, niezgodny SHA-256, niezgodny podpis, brak pozwolenia na instalację, brak sieci.

## Od wersji 0.2

- Pełny dostęp do plików: nadaj uprawnienie, wróć do aplikacji – otwiera się katalog główny pamięci; cofnij uprawnienie – źródło znika.
- Android 11–12: Ustawienia → „Dostęp do Android/data”; Android 13+: komunikat o blokadzie systemu.
- Kosz: usuń plik i folder z pamięci wewnętrznej, przywróć (także gdy w miejscu docelowym jest już taka nazwa), opróżnij kosz.
- Konflikty przy wklejaniu: Zachowaj oba, Pomiń, Zastąp (plik oraz scalanie folderów); kopiowanie w tym samym folderze tworzy „nazwa (2)”.
- Przenoszenie w obrębie pamięci wewnętrznej działa natychmiast (bez kopiowania danych).
- ZIP: spakuj pliki i foldery, podejrzyj archiwum, rozpakuj archiwum z Windows (nazwy CP437) i z polskimi znakami.
- Wyszukiwanie w podfolderach z `*`, zatrzymanie wyszukiwania, „Pokaż w folderze”.
- Edytor tekstu: zmiana, zapis, zamknięcie z niezapisanymi zmianami. Przeglądarka zdjęć: przesuwanie i powiększanie.
- Transfer w tle: zablokuj ekran podczas kopiowania dużego pliku – powiadomienie pokazuje postęp, operacja kończy się poprawnie.
- Aktualizacja z poziomu aplikacji z wersji 0.1.0 do najnowszego wydania (ten sam certyfikat).
