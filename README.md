# monika-java

Monika: cửa hàng ứng dụng Java (J2ME, MIDP 2.0 / CLDC 1.1) cho Nokia S60 by AOW.VN.

- `client/`: MIDlet Monika (nguồn Java kiểu 1.3, chỉ dùng API của CLDC và MIDP)
- `catalog/`: catalog mẫu (nguồn của `catalog_sample.bin`)
- `tools/`: `build.sh` dựng JAR/JAD, `GenAssets.java` sinh icon và catalog, `proguard.pro`
- `dist/`: bản dựng đầu tiên `Monika.jar` + `Monika.jad`
- `docs/first-build.md`: những gì có trong bản dựng, cách dựng, quyết định đã ghi lại

Dựng: `./tools/build.sh` (cần JDK 8 trở lên, curl, unzip).
