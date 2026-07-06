#!/bin/bash

# Definim calea către folderul Downloads al utilizatorului curent
OUTPUT_FILE="$HOME/Downloads/voxcommander_source_code.txt"

# Golim fișierul dacă există deja de la o rulare anterioară
> "$OUTPUT_FILE"

echo "Încep colectarea codului sursă..."

# Folosim find pentru a parcurge recursiv directorul curent
# -type f : doar fișiere
# Filtrăm doar extensiile de cod sursă relevante
# Excludem directoarele ascunse (ex: .git, .gradle) și directoarele de build
find . -type f \( \
    -name "*.kt" -o \
    -name "*.java" -o \
    -name "*.xml" -o \
    -name "*.cpp" -o \
    -name "*.c" -o \
    -name "*.h" -o \
    -name "*.hpp" -o \
    -name "*.cmake" -o \
    -name "CMakeLists.txt" -o \
    -name "*.gradle" -o \
    -name "*.properties" -o \
    -name "*.json" \
\) \
-not -path "*/\.*" \
-not -path "*/build/*" \
-not -path "*/app/build/*" | sort | while read -r file; do
    
    # Formatare optimizată pentru LLM-uri
    echo "================================================================================" >> "$OUTPUT_FILE"
    echo "File: $file" >> "$OUTPUT_FILE"
    echo "================================================================================" >> "$OUTPUT_FILE"
    
    # Adăugăm conținutul fișierului
    cat "$file" >> "$OUTPUT_FILE"
    
    # Două rânduri libere la final pentru lizibilitate
    echo -e "\n\n" >> "$OUTPUT_FILE"
    
done

echo "Gata! Fișierul a fost salvat cu succes în: $OUTPUT_FILE"