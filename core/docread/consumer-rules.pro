# Parsed from JSON by Gson, so their field names must survive R8 — a stripped field is not a compile
# error and not a unit-test failure: the release build reads an empty schema and refuses it. These
# travel with the module rather than being repeated in every consumer's own rules, since it is this
# module that decides what it parses.
-keep class com.voxapps.docread.ReceiptTemplateSchema { *; }
-keep class com.voxapps.docread.ColumnTemplateEntry { *; }
-keep class com.voxapps.docread.CaptionTemplateEntry { *; }
-keep class com.voxapps.docread.HeaderTemplateEntry { *; }
-keep class com.voxapps.docread.ItemTemplateEntry { *; }
-keep class com.voxapps.docread.FooterTemplateEntry { *; }
