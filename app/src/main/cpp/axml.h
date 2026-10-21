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

}  // namespace axml
