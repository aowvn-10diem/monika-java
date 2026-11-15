# ProGuard: thu nhỏ, làm rối và preverify cho CLDC/MIDP trong một bước.
# Các -injars/-outjars/-libraryjars do tools/build.sh truyền vào dòng lệnh.
-microedition
-dontnote
-dontwarn
-optimizationpasses 2
-overloadaggressively
-repackageclasses ''
-allowaccessmodification
-keep public class vn.aowvn.monika.MonikaMidlet
