# Pen Drive Manager (Android) — strictly exFAT

USB OTG se connect hui **exFAT** pendrive ko directly browse/copy/delete karne
ke liye. Website/browser-based tools ke bajaye ye ek real Android app hai jo
Android ke USB Host API se seedha kaam karta hai.

## Yeh kaise bana hai (important, pura padhna)

Koi free/open-source Android library exFAT ko properly support nahi karti hai
(maine check kiya — sirf FAT32 milta hai unme). Isliye is app mein **exFAT
support poora khud se likha gaya hai**, low-level se:

1. `driver/UsbBulkTransport.kt` — Android USB Host API se pendrive ka
   mass-storage interface claim karta hai.
2. `driver/ScsiBlockDevice.kt` — USB Mass Storage "Bulk-Only Transport"
   protocol (CBW/CSW) aur SCSI Read(10)/Write(10)/ReadCapacity(10) commands.
   Yeh wahi standard hai jo Windows/Linux bhi internally use karte hain.
3. `fs/ExFatFileSystem.kt` — Microsoft ki publicly documented exFAT
   specification ke hisaab se: boot sector, FAT cluster chains, directory
   entries (file/stream/filename records) parse karta hai.

## ⚠️ Bahut zaroori: safety scope

Maine yeh code is session mein likha hai bina kisi real pendrive/emulator par
test kiye (yahan Android SDK/USB hardware available nahi tha). Yeh **raw disk
bytes ke saath directly kaam karta hai** — isliye:

| Operation | Status |
|---|---|
| Browse / list files | ✅ Implemented |
| File copy (pendrive → phone) | ✅ Implemented |
| Delete | ⚠️ "Soft delete" — sirf directory entry hide hoti hai, cluster/FAT/allocation-bitmap jaan-boojh kar touch nahi kiya (taaki bug se baaki drive corrupt na ho) |
| Naya file banana / edit karna | ❌ Nahi kiya — allocation-bitmap likhna bina real-device testing ke risky hai |
| GPT-partitioned drives | ❌ Sirf MBR partitions (zyadatar chhoti/purani pendrives MBR hoti hain) |

**Pehli baar use karte waqt:**
- Pehle kisi **non-important** exFAT pendrive par test karo.
- **Read/copy pehle try karo** — yeh sabse safe operation hai (kuch bhi likhta nahi disk par).
- Agar tumhara asli maksad corrupted drive se data nikalna hai, to bas
  "Phone mein copy karein" use karo — delete ki zaroorat hi nahi.
- Agar koi cheez ajeeb lage (galat file names, crash, waghera), turant band karke
  mujhe error message batao — main fix kar dunga.

## Kaise open karein
1. [Android Studio](https://developer.android.com/studio) install karein.
2. Is poore `PenDriveManager` folder ko File > Open karein.
3. Gradle sync hone dein (koi external library dependency nahi hai is baar).
4. Phone connect karke Run karein, ya APK build karein.
5. exFAT pendrive OTG adapter se lagayein.

## Project structure
```
app/src/main/java/com/pendrivemanager/usb/
  MainActivity.kt        -> USB permission, mount, navigation, UI actions
  FileAdapter.kt          -> RecyclerView list
  driver/
    UsbBulkTransport.kt   -> raw USB bulk transfer
    ScsiBlockDevice.kt    -> SCSI commands (sector read/write)
  fs/
    ExFatFileSystem.kt    -> exFAT parser (boot sector, FAT, directories)
    ExFatEntry.kt          -> ek file/folder ka model
```

## Agar kabhi likhna (write/create) bhi chahiye
Allocation bitmap + FAT properly update karna hoga naye clusters allocate
karne ke liye — yeh agla step hai agar read/delete phone par sahi kaam kar
jaye. Filhaal maine jaan-boojh kar skip kiya hai safety ke liye.
sha256:d9c7bfc2693d2900d6b95344844244d1028e00310fea8a74d5dec3dd28dfbc9d
