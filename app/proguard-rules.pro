# Keep serialization and JSCH classes intact for SSH/MCP/LLM JSON parsing
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
-keepclassmembers class com.forgemind.app.data.model.** { *; }
-keepclassmembers class com.forgemind.app.mcp.** { *; }
-keep class com.jcraft.jsch.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn com.tom_roush.pdfbox.**
