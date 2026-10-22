// Parser + patcher untuk AndroidManifest.xml biner (AXML). Murni C++, tanpa JNI.
#pragma once
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

namespace axml {

constexpr uint16_t kXml = 0x0003, kPool = 0x0001, kResMap = 0x0180, kStartEl = 0x0102;
constexpr uint32_t kResMin = 0x0101020c, kResTarget = 0x01010270, kResSplitReq = 0x01010591;
constexpr uint32_t kResVerCode = 0x0101021b, kResVerName = 0x0101021c;
constexpr uint8_t kTypeInt = 0x10, kTypeHex = 0x11, kTypeBool = 0x12;
constexpr uint32_t kNone = 0xFFFFFFFFu;

template <class T> inline T rd(const uint8_t* p) { T v; std::memcpy(&v, p, sizeof(T)); return v; }
template <class T> inline void wr(uint8_t* p, T v) { std::memcpy(p, &v, sizeof(T)); }

struct Info {
    std::string pkg;
    int minSdk = -1, targetSdk = -1, splitReq = -1, versionCode = -1, perms = 0;
    std::string versionName;
};

inline void readPool(const uint8_t* d, size_t off, size_t sz, std::vector<std::string>& out) {
    if (sz < 28) return;
    const uint8_t* c = d + off;
    const uint8_t* end = c + sz;
    uint32_t hs = rd<uint16_t>(c + 2), cnt = rd<uint32_t>(c + 8);
    uint32_t flags = rd<uint32_t>(c + 16), start = rd<uint32_t>(c + 20);
    if ((uint64_t)hs + (uint64_t)cnt * 4 > sz || start > sz) return;
    const bool utf8 = (flags & 0x100) != 0;
    out.reserve(cnt);
    for (uint32_t i = 0; i < cnt; i++) {
        uint64_t pos = (uint64_t)start + rd<uint32_t>(c + hs + (size_t)i * 4);
        std::string s;
        if (pos + 2 <= sz) {
            const uint8_t* p = c + pos;
            if (utf8) {
                auto len8 = [&](uint32_t& l) -> bool {
                    if (p >= end) return false;
                    l = *p++;
                    if (l & 0x80) { if (p >= end) return false; l = ((l & 0x7f) << 8) | *p++; }
                    return true;
                };
                uint32_t chars = 0, bytes = 0;
                if (len8(chars) && len8(bytes) && p + bytes <= end)
                    s.assign(reinterpret_cast<const char*>(p), bytes);
            } else {
                uint32_t l = rd<uint16_t>(p); p += 2;
                if ((l & 0x8000) && p + 2 <= end) { l = ((l & 0x7fff) << 16) | rd<uint16_t>(p); p += 2; }
                if (p + (uint64_t)l * 2 <= end)
                    for (uint32_t k = 0; k < l; k++) {
                        uint16_t ch = rd<uint16_t>(p + (size_t)k * 2);
                        s.push_back(ch < 0x80 ? (char)ch : '?');
                    }
            }
        }
        out.push_back(std::move(s));
    }
}

inline void setInt(uint8_t* a, int v) {
    wr<uint32_t>(a + 8, kNone);
    wr<uint16_t>(a + 12, 8);
    a[14] = 0;
    a[15] = kTypeInt;
    wr<uint32_t>(a + 16, (uint32_t)v);
}

// newMin/newTarget < 0 = jangan ubah. clearSplit = ubah isSplitRequired menjadi false.
inline bool process(uint8_t* d, size_t n, int newMin, int newTarget, bool clearSplit, Info& info) {
    if (n < 8 || rd<uint16_t>(d) != kXml) return false;
    size_t pos = rd<uint16_t>(d + 2);
    std::vector<std::string> pool;
    std::vector<uint32_t> ids;
    static const std::string kEmpty;
    auto str = [&](uint32_t i) -> const std::string& { return i < pool.size() ? pool[i] : kEmpty; };

    while (pos + 8 <= n) {
        uint16_t type = rd<uint16_t>(d + pos), hs = rd<uint16_t>(d + pos + 2);
        uint32_t sz = rd<uint32_t>(d + pos + 4);
        if (sz < 8 || pos + sz > n) break;
        if (type == kPool) {
            readPool(d, pos, sz, pool);
        } else if (type == kResMap) {
            for (size_t o = pos + hs; o + 4 <= pos + sz; o += 4) ids.push_back(rd<uint32_t>(d + o));
        } else if (type == kStartEl && sz >= 36) {
            uint8_t* c = d + pos;
            uint32_t nameIdx = rd<uint32_t>(c + 20);
            uint16_t aStart = rd<uint16_t>(c + 24), aSize = rd<uint16_t>(c + 26), aCnt = rd<uint16_t>(c + 28);
            const std::string& el = str(nameIdx);
            const bool isManifest = el == "manifest", isSdk = el == "uses-sdk";
            if (el == "uses-permission" || el == "uses-permission-sdk-23") info.perms++;
            if (aSize >= 20) {
                for (uint16_t i = 0; i < aCnt; i++) {
                    size_t ao = 16 + (size_t)aStart + (size_t)i * aSize;
                    if (ao + 20 > sz) break;
                    uint8_t* a = c + ao;
                    uint32_t an = rd<uint32_t>(a + 4), raw = rd<uint32_t>(a + 8), data = rd<uint32_t>(a + 16);
                    uint8_t dt = a[15];
                    uint32_t id = an < ids.size() ? ids[an] : 0;
                    const std::string& nm = str(an);
                    const bool isInt = dt == kTypeInt || dt == kTypeHex;

                    if (isManifest && nm == "package" && info.pkg.empty() && raw != kNone) info.pkg = str(raw);
                    if (isManifest && isInt && (id == kResVerCode || (id == 0 && nm == "versionCode"))) info.versionCode = (int)data;
                    if (isManifest && (id == kResVerName || (id == 0 && nm == "versionName")))
                        info.versionName = str(raw != kNone ? raw : data);
                    if (isSdk && isInt && (id == kResMin || (id == 0 && nm == "minSdkVersion"))) {
                        info.minSdk = (int)data;
                        if (newMin >= 0 && (int)data != newMin) setInt(a, newMin);
                    }
                    if (isSdk && isInt && (id == kResTarget || (id == 0 && nm == "targetSdkVersion"))) {
                        info.targetSdk = (int)data;
                        if (newTarget >= 0 && (int)data != newTarget) setInt(a, newTarget);
                    }
                    if (dt == kTypeBool && (id == kResSplitReq || (id == 0 && nm == "isSplitRequired"))) {
                        info.splitReq = data ? 1 : 0;
                        if (clearSplit && data) wr<uint32_t>(a + 16, 0);
                    }
                }
            }
        }
        pos += sz;
    }
    return true;
}


// ===== Dukungan tulis: memperbesar string pool dan mengganti nilai atribut string =====

inline void appendU32(std::vector<uint8_t>& v, uint32_t x) {
    v.push_back((uint8_t)x); v.push_back((uint8_t)(x >> 8));
    v.push_back((uint8_t)(x >> 16)); v.push_back((uint8_t)(x >> 24));
}
inline void appendU16(std::vector<uint8_t>& v, uint16_t x) {
    v.push_back((uint8_t)x); v.push_back((uint8_t)(x >> 8));
}

// Encode satu string baru ke format pool (UTF-8 atau UTF-16 sesuai flag pool asli).
inline void encodeStr(std::vector<uint8_t>& data, const std::string& s, bool utf8) {
    if (utf8) {
        uint32_t chars = (uint32_t)s.size();  // ASCII-only, chars == bytes
        auto putLen = [&](uint32_t l) {
            if (l < 0x80) data.push_back((uint8_t)l);
            else { data.push_back((uint8_t)(0x80 | (l >> 8))); data.push_back((uint8_t)l); }
        };
        putLen(chars);
        putLen(chars);
        data.insert(data.end(), s.begin(), s.end());
        data.push_back(0);
    } else {
        uint32_t chars = (uint32_t)s.size();
        if (chars < 0x8000) appendU16(data, (uint16_t)chars);
        else { appendU16(data, (uint16_t)(0x8000 | (chars >> 16))); appendU16(data, (uint16_t)chars); }
        for (char c : s) appendU16(data, (uint16_t)(uint8_t)c);
        appendU16(data, 0);
    }
}

// Menambah satu atau lebih string baru ke string pool AXML dan mengembalikan indeksnya.
// Mengubah ukuran seluruh buffer karena pool ditulis ulang total (layout: header, offset[], data[]).
// Hanya mendukung pool tanpa style span (styleCount == 0), yang berlaku untuk manifest hasil AAPT/AAPT2.
struct PoolWriter {
    size_t poolOff = 0, poolSize = 0;
    uint32_t count = 0, flags = 0;
    bool ok = false;
};

inline PoolWriter findPool(const std::vector<uint8_t>& d) {
    PoolWriter pw;
    if (d.size() < 8 || rd<uint16_t>(d.data()) != kXml) return pw;
    size_t pos = rd<uint16_t>(d.data() + 2);
    while (pos + 8 <= d.size()) {
        uint16_t type = rd<uint16_t>(d.data() + pos);
        uint32_t sz = rd<uint32_t>(d.data() + pos + 4);
        if (sz < 8 || pos + sz > d.size()) break;
        if (type == kPool) {
            uint32_t styleCount = rd<uint32_t>(d.data() + pos + 12);
            if (styleCount == 0) {
                pw.poolOff = pos; pw.poolSize = sz;
                pw.count = rd<uint32_t>(d.data() + pos + 8);
                pw.flags = rd<uint32_t>(d.data() + pos + 16);
                pw.ok = true;
            }
            return pw;
        }
        pos += sz;
    }
    return pw;
}

// newStrings ditambahkan ke akhir pool; mengembalikan indeks string pertama yang baru (berurutan).
inline int appendPoolStrings(std::vector<uint8_t>& d, const std::vector<std::string>& newStrings, int& firstIndexOut) {
    PoolWriter pw = findPool(d);
    if (!pw.ok || newStrings.empty()) return -1;
    const uint8_t* base = d.data() + pw.poolOff;
    bool utf8 = (pw.flags & 0x100) != 0;
    uint32_t hs = rd<uint16_t>(base + 2);
    uint32_t oldCount = pw.count;
    uint32_t stringsStart = rd<uint32_t>(base + 20);

    // Kumpulkan offset lama (relatif ke stringsStart) dan data string lama mentah.
    std::vector<uint32_t> oldOffsets(oldCount);
    for (uint32_t i = 0; i < oldCount; i++) oldOffsets[i] = rd<uint32_t>(base + hs + (size_t)i * 4);
    size_t dataStart = pw.poolOff + stringsStart;
    size_t dataLen = pw.poolSize - stringsStart;
    std::vector<uint8_t> oldData(d.begin() + dataStart, d.begin() + dataStart + dataLen);

    // Encode string baru.
    std::vector<uint8_t> newData;
    std::vector<uint32_t> newOffsets;
    for (auto& s : newStrings) { newOffsets.push_back((uint32_t)(oldData.size() + newData.size())); encodeStr(newData, s, utf8); }

    uint32_t newCount = oldCount + (uint32_t)newStrings.size();
    std::vector<uint8_t> chunk;
    appendU16(chunk, kPool); appendU16(chunk, (uint16_t)hs);
    size_t sizePos = chunk.size(); appendU32(chunk, 0);  // diisi belakangan
    appendU32(chunk, newCount);
    appendU32(chunk, 0);               // styleCount tetap 0
    appendU32(chunk, pw.flags);
    size_t stringsStartPos = chunk.size(); appendU32(chunk, 0);  // diisi belakangan
    appendU32(chunk, 0);               // stylesStart = 0 (tidak ada style)
    for (size_t i = chunk.size(); i < hs; i++) chunk.push_back(0);  // jaga-jaga jika header lebih besar

    for (uint32_t off : oldOffsets) appendU32(chunk, off);
    for (uint32_t off : newOffsets) appendU32(chunk, off);

    uint32_t newStringsStart = (uint32_t)chunk.size();
    chunk.insert(chunk.end(), oldData.begin(), oldData.end());
    chunk.insert(chunk.end(), newData.begin(), newData.end());
    // Pool chunk harus kelipatan 4 byte.
    while (chunk.size() % 4 != 0) chunk.push_back(0);

    wr<uint32_t>(chunk.data() + sizePos, (uint32_t)chunk.size());
    wr<uint32_t>(chunk.data() + stringsStartPos, newStringsStart);

    long delta = (long)chunk.size() - (long)pw.poolSize;
    d.erase(d.begin() + pw.poolOff, d.begin() + pw.poolOff + pw.poolSize);
    d.insert(d.begin() + pw.poolOff, chunk.begin(), chunk.end());

    uint32_t totalSize = rd<uint32_t>(d.data() + 4);
    wr<uint32_t>(d.data() + 4, (uint32_t)((long)totalSize + delta));

    firstIndexOut = (int)oldCount;
    return firstIndexOut;
}

inline void setStringAttrByIndex(uint8_t* a, int strIndex) {
    wr<uint32_t>(a + 8, (uint32_t)strIndex);   // rawValue
    wr<uint16_t>(a + 12, 8);                    // typedValue.size
    a[14] = 0;
    a[15] = 0x03;                                // TYPE_STRING
    wr<uint32_t>(a + 16, (uint32_t)strIndex);    // typedValue.data
}

constexpr uint32_t kResName = 0x01010003, kResPermission = 0x01010006;

// Mengubah package manifest, label, versi, authorities provider, DAN setiap <permission>/
// <permission-group> yang namanya diawali packagename asli (pola umum izin kustom buatan
// AndroidX, mis. "pkg.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION") — ini mencegah
// INSTALL_FAILED_DUPLICATE_PERMISSION saat hasil klon dipasang berdampingan dengan aslinya.
// Setiap <uses-permission> dan atribut android:permission yang merujuk nama lama ikut diubah.
// Mengembalikan true bila buffer dipatch (walau sebagian tidak ditemukan, itu tetap dianggap sukses).
inline bool clonePatch(std::vector<uint8_t>& d, const std::string& origPkg, const std::string& newPkg,
                        const std::string& newLabel, const std::string& newVerName, int newVerCode,
                        const std::string& authSuffix) {
    if (d.size() < 8 || rd<uint16_t>(d.data()) != kXml) return false;

    std::vector<std::string> toAdd;
    if (!newPkg.empty()) toAdd.push_back(newPkg);
    if (!newLabel.empty()) toAdd.push_back(newLabel);
    if (!newVerName.empty()) toAdd.push_back(newVerName);

    // --- Pass 1: baca pool + kumpulkan authorities dan nama <permission>/<permission-group> yang perlu diganti ---
    std::vector<std::string> authValues;
    std::vector<std::pair<uint32_t, std::string>> permRenames;  // (indeks string lama, nilai baru)
    {
        std::vector<std::string> pool; std::vector<uint32_t> ids;
        PoolWriter pw0 = findPool(d);
        readPool(d.data(), pw0.poolOff, pw0.poolSize, pool);
        size_t pos = rd<uint16_t>(d.data() + 2);
        while (pos + 8 <= d.size()) {
            uint16_t type = rd<uint16_t>(d.data() + pos);
            uint32_t sz = rd<uint32_t>(d.data() + pos + 4);
            if (sz < 8 || pos + sz > d.size()) break;
            if (type == kResMap) { ids.clear(); for (size_t o = pos + rd<uint16_t>(d.data()+pos+2); o + 4 <= pos + sz; o += 4) ids.push_back(rd<uint32_t>(d.data() + o)); }
            else if (type == kStartEl && sz >= 36) {
                const uint8_t* c = d.data() + pos;
                uint32_t nameIdx = rd<uint32_t>(c + 20);
                const std::string el = nameIdx < pool.size() ? pool[nameIdx] : std::string();
                uint16_t aStart = rd<uint16_t>(c + 24), aSize = rd<uint16_t>(c + 26), aCnt = rd<uint16_t>(c + 28);
                for (uint16_t i = 0; i < aCnt && aSize >= 20; i++) {
                    size_t ao = 16 + (size_t)aStart + (size_t)i * aSize;
                    if (ao + 20 > sz) break;
                    const uint8_t* a = c + ao;
                    uint32_t an = rd<uint32_t>(a + 4), raw = rd<uint32_t>(a + 8);
                    uint32_t id = an < ids.size() ? ids[an] : 0;
                    const std::string nm = an < pool.size() ? pool[an] : std::string();
                    if (!authSuffix.empty() && el == "provider") {
                        bool isAuth = id == 0x01010018 || nm == "authorities";
                        if (isAuth && raw != kNone && raw < pool.size()) authValues.push_back(pool[raw] + "." + authSuffix);
                    }
                    if (!origPkg.empty() && !newPkg.empty() && (el == "permission" || el == "permission-group")) {
                        bool isName = id == kResName || nm == "name";
                        if (isName && raw != kNone && raw < pool.size()) {
                            const std::string& v = pool[raw];
                            if (v.rfind(origPkg + ".", 0) == 0)
                                permRenames.push_back({raw, newPkg + v.substr(origPkg.size())});
                        }
                    }
                }
            }
            pos += sz;
        }
    }
    for (auto& v : authValues) toAdd.push_back(v);
    for (auto& [idx_, v] : permRenames) { (void)idx_; toAdd.push_back(v); }
    if (toAdd.empty() && newVerCode < 0) return true;  // tidak ada yang perlu diubah

    int firstIdx = -1;
    if (!toAdd.empty() && appendPoolStrings(d, toAdd, firstIdx) < 0) return false;
    int cursor = firstIdx;
    int pkgIdx = -1, labelIdx = -1, verNameIdx = -1, authBase = -1, permBase = -1;
    if (!newPkg.empty()) { pkgIdx = cursor++; }
    if (!newLabel.empty()) { labelIdx = cursor++; }
    if (!newVerName.empty()) { verNameIdx = cursor++; }
    if (!authValues.empty()) { authBase = cursor; cursor += (int)authValues.size(); }
    if (!permRenames.empty()) { permBase = cursor; cursor += (int)permRenames.size(); }

    // Peta: indeks string lama (nama permission) -> indeks string baru di pool.
    std::vector<std::pair<uint32_t, uint32_t>> permMap;
    for (size_t i = 0; i < permRenames.size(); i++) permMap.push_back({permRenames[i].first, (uint32_t)(permBase + (int)i)});
    auto mapPerm = [&](uint32_t oldIdx) -> int {
        for (auto& [o, n] : permMap) if (o == oldIdx) return (int)n;
        return -1;
    };

    // --- Pass 2: tulis ulang atribut ---
    std::vector<std::string> pool; std::vector<uint32_t> ids;
    PoolWriter pw = findPool(d);
    readPool(d.data(), pw.poolOff, pw.poolSize, pool);
    size_t pos = rd<uint16_t>(d.data() + 2);
    int authCursor = 0;
    while (pos + 8 <= d.size()) {
        uint16_t type = rd<uint16_t>(d.data() + pos);
        uint32_t sz = rd<uint32_t>(d.data() + pos + 4);
        if (sz < 8 || pos + sz > d.size()) break;
        if (type == kResMap) { ids.clear(); for (size_t o = pos + rd<uint16_t>(d.data()+pos+2); o + 4 <= pos + sz; o += 4) ids.push_back(rd<uint32_t>(d.data() + o)); }
        else if (type == kStartEl && sz >= 36) {
            uint8_t* c = d.data() + pos;
            uint32_t nameIdx = rd<uint32_t>(c + 20);
            const std::string& el = nameIdx < pool.size() ? pool[nameIdx] : std::string();
            uint16_t aStart = rd<uint16_t>(c + 24), aSize = rd<uint16_t>(c + 26), aCnt = rd<uint16_t>(c + 28);
            for (uint16_t i = 0; i < aCnt && aSize >= 20; i++) {
                size_t ao = 16 + (size_t)aStart + (size_t)i * aSize;
                if (ao + 20 > sz) break;
                uint8_t* a = c + ao;
                uint32_t an = rd<uint32_t>(a + 4), raw = rd<uint32_t>(a + 8);
                uint32_t id = an < ids.size() ? ids[an] : 0;
                const std::string& nm = an < pool.size() ? pool[an] : std::string();
                if (pkgIdx >= 0 && el == "manifest" && nm == "package") setStringAttrByIndex(a, pkgIdx);
                if (newVerCode >= 0 && el == "manifest" && (id == kResVerCode || nm == "versionCode")) setInt(a, newVerCode);
                if (verNameIdx >= 0 && el == "manifest" && (id == kResVerName || nm == "versionName")) setStringAttrByIndex(a, verNameIdx);
                if (labelIdx >= 0 && el == "application" && (id == 0x01010001 || nm == "label")) setStringAttrByIndex(a, labelIdx);
                if (!authSuffix.empty() && el == "provider" && (id == 0x01010018 || nm == "authorities") && authCursor < (int)authValues.size())
                    setStringAttrByIndex(a, authBase + authCursor++);
                if ((el == "permission" || el == "permission-group") && (id == kResName || nm == "name") && raw != kNone) {
                    int ni = mapPerm(raw);
                    if (ni >= 0) setStringAttrByIndex(a, (uint32_t)ni);
                }
                if ((el == "uses-permission" || el == "uses-permission-sdk-23") && (id == kResName || nm == "name") && raw != kNone) {
                    int ni = mapPerm(raw);
                    if (ni >= 0) setStringAttrByIndex(a, (uint32_t)ni);
                }
                if ((id == kResPermission || nm == "permission") && raw != kNone) {
                    int ni = mapPerm(raw);
                    if (ni >= 0) setStringAttrByIndex(a, (uint32_t)ni);
                }
            }
        }
        pos += sz;
    }
    return true;
}

}  // namespace axml

