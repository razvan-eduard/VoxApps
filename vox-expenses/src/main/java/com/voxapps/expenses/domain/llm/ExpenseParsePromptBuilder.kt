package com.voxapps.expenses.domain.llm

/**
 * Language-agnostic semantic prompt builder for spoken expenses.
 * Focuses on entity extraction (vendor, item, quantity, price) and logical arithmetic.
 */
object ExpenseParsePromptBuilder {
    fun build(rawText: String, existingCategories: List<String>, defaultCurrency: String, languageCode: String): String {
        val categoriesLine = if (existingCategories.isEmpty()) {
            "No categories exist yet."
        } else {
            "Existing categories: ${existingCategories.joinToString(", ")}."
        }
        
        return """
            Identify and extract expense data from the following spoken text. 
            
            CORE LOGIC:
            1. ENTITIES: Identify the vendor, items, quantities, and prices. 
            2. UNIT PRICE: Words like "each", "per unit", "bucata", or "la [price]" indicate 
               the unitPrice. You MUST not confuse these with the total.
            3. ARITHMETIC: For each item, total = quantity * unitPrice. Set the root 
               "totalAmount" to the grand sum of all these subtotals.
               Example: "10 breads at 10 each" -> qty: 10, unitPrice: 10.0, totalAmount: 100.0.
            4. FALLBACK: If no arithmetic is possible, use the single mentioned amount.
            4. CATEGORIZATION: $categoriesLine Match exactly if possible, or suggest a new one.
            
            TECHNICAL CONSTRAINTS:
            - Respond in language: "$languageCode".
            - Default currency: "$defaultCurrency".
            - Output: Return ONLY raw JSON. No text, no markdown.
            - Format: {"title": "...", "totalAmount": 100.0, "currency": "...", "vendor": "...", 
              "category": "...", "items": [{"name": "...", "quantity": 10.0, "unitPrice": 10.0}]}

            INPUT TEXT:
            $rawText
        """.trimIndent()
    }
}
