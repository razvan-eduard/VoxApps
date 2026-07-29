# Plan de Implementare: Afișare Zi Curentă (Chiar și Gole) în Widget-uri

Acest plan vizează asigurarea prezenței secțiunii "Today" în widget-urile de pe ecranul de pornire, chiar și atunci când nu există evenimente sau cheltuieli înregistrate pentru ziua respectivă.

## 1. Localizare (Strings)
Vom adăuga o cheie comună pentru mesajul de stare goală:
- **EN**: `widget_nothing_today`: "Nothing for today"
- **RO**: `widget_nothing_today`: "Nimic pentru astăzi"
- **DE**: `widget_nothing_today`: "Nichts für heute"
- **FR**: `widget_nothing_today`: "Rien pour aujourd'hui"

## 2. Actualizare Logică Grupare (`CalendarWidget.kt` & `ExpensesWidget.kt`)
- Vom modifica procesul de filtrare și grupare pentru a asigura că cheia `today` există mereu în harta de date.
- Vom elimina `return early` atunci când lista totală este goală, forțând afișarea secțiunii "Today".

## 3. Stilizare Stare Goală
Dacă grupul "Today" este gol:
- Se va afișa header-ul tip "Pill" (conform designului anterior).
- În interior, va apărea textul definit la punctul 1, stilizat discret cu `GlanceTheme.colors.onSurfaceVariant`.

## User Review Required

> [!IMPORTANT]
> **Prioritizarea "Today"**: Această schimbare va face ca widget-ul să nu mai fie niciodată complet gol sau să afișeze doar mesaje generice. "Today" va fi ancora vizuală permanentă a widget-ului.

## Plan de Verificare
- [ ] Verificare pe dispozitiv: Dacă nu am evenimente azi, dar am mâine -> "Today" apare cu textul "Nothing for today", urmat de evenimentele de mâine.
- [ ] Verificare pe dispozitiv: Dacă nu am niciun eveniment viitor -> Apare doar secțiunea "Today" goală.
- [ ] Verificare consistență: Aceeași logică în ambele widget-uri (Calendar și Expenses).
