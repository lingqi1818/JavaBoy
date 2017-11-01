/*

JavaBoy
                                  
COPYRIGHT (C) 2001 Neil Millstone and The Victoria University of Manchester
                                                                         ;;;
This program is free software; you can redistribute it and/or modify it
under the terms of the GNU General Public License as published by the Free
Software Foundation; either version 2 of the License, or (at your option)
any later version.        

This program is distributed in the hope that it will be useful, but WITHOUT
ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License for
more details.


You should have received a copy of the GNU General Public License along with
this program; if not, write to the Free Software Foundation, Inc., 59 Temple
Place - Suite 330, Boston, MA 02111-1307, USA.

*/


import java.awt.*;
import java.awt.image.*;
import java.lang.*;
import java.io.*;
import java.applet.*;
import java.net.*;
import java.awt.event.KeyListener;
import java.awt.event.WindowListener;
import java.awt.event.ActionListener;
import java.awt.event.ComponentListener;
import java.awt.event.ItemListener;
import java.awt.event.KeyEvent;
import java.awt.event.WindowEvent;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentEvent;
import java.awt.event.ItemEvent;
import java.util.StringTokenizer;
import javax.sound.sampled.*;

/** This class represents the game cartridge and contains methods to load the ROM and battery RAM
 *  (if necessary) from disk or over the web, and handles emulation of ROM mappers and RAM banking.
 *  It is missing emulation of MBC3 (this is very rare).
 */

class Cartridge {
 /** Translation between ROM size byte contained in the ROM header, and the number
  *  of 16Kb ROM banks the cartridge will contain
  */
 final int[][] romSizeTable = {{0, 2}, {1, 4}, {2, 8}, {3, 16}, {4, 32},
             {5, 64}, {6, 128}, {7, 256}, {0x52, 72}, {0x53, 80}, {0x54, 96}};

 /** Contains strings of the standard names of the cartridge mapper chips, indexed by
  *  cartridge type
  */
 final String[] cartTypeTable =
    {"ROM Only",             /* 00 */
     "ROM+MBC1",             /* 01 */
     "ROM+MBC1+RAM",         /* 02 */
     "ROM+MBC1+RAM+BATTERY", /* 03 */
     "Unknown",              /* 04 */
     "ROM+MBC2",             /* 05 */
     "ROM+MBC2+BATTERY",     /* 06 */
     "Unknown",              /* 07 */
     "ROM+RAM",              /* 08 */
     "ROM+RAM+BATTERY",      /* 09 */
     "Unknown",              /* 0A */
     "Unsupported ROM+MMM01+SRAM",             /* 0B */
     "Unsupported ROM+MMM01+SRAM+BATTERY",     /* 0C */
     "Unknown",                                /* 0D */
     "Unsupported ROM+MBC3+TIMER+BATTERY",     /* 0E */
     "Unsupported ROM+MBC3+TIMER+RAM+BATTERY", /* 0F */
     "Unsupported ROM+MBC3",                   /* 10 */
     "Unsupported ROM+MBC3+RAM",               /* 11 */
     "Unsupported ROM+MBC3+RAM+BATTERY",       /* 12 */
     "Unknown",              /* 13 */
     "Unknown",              /* 14 */
     "Unknown",              /* 15 */
     "Unknown",              /* 16 */
     "Unknown",              /* 17 */
     "Unknown",              /* 18 */
     "ROM+MBC5",             /* 19 */
     "ROM+MBC5+RAM",         /* 1A */
     "ROM+MBC5+RAM+BATTERY", /* 1B */
     "ROM+MBC5+RUMBLE",      /* 1C */
     "ROM+MBC5+RUMBLE+RAM",  /* 1D */
     "ROM+MBC5+RUMBLE+RAM+BATTERY"  /* 1E */  };

 /** Contains the complete ROM image of the cartridge */
 public byte[] rom;

 /** Contains the RAM on the cartridge */
 public byte[] ram = new byte[0x10000];

 /** Number of 16Kb ROM banks */
 int numBanks;

 /** Cartridge type - index into cartTypeTable[][] */
 int cartType;

 /** Starting address of the ROM bank at 0x4000 in CPU address space */
 int pageStart = 0x4000;

 /** The bank number which is currently mapped at 0x4000 in CPU address space */
 int currentBank = 1;

 /** The bank which has been saved when the debugger changes the ROM mapping.  The mapping is
  *  restored from this register when execution resumes */
 int savedBank = -1;

 /** The RAM bank number which is currently mapped at 0xA000 in CPU address space */
 int ramBank;
 int ramPageStart;

 boolean mbc1LargeRamMode = false;
 boolean ramEnabled, disposed = false;
 Component applet;

 /** The filename of the currently loaded ROM */
 String romFileName;

 boolean cartridgeReady = false;

 /** Create a cartridge object, loading ROM and any associated battery RAM from the cartridge
  *  filename given.  Loads via the web if JavaBoy is running as an applet */
 public Cartridge(String romFileName, Component a) {
  applet = a; /* 5823 */
  this.romFileName = romFileName;
  InputStream is = null;
  try {
   if (JavaBoy.runningAsApplet) {
    Applet myApplet = (Applet) a;
    is = new URL(myApplet.getDocumentBase(), romFileName).openStream();
   } else {
    is = new FileInputStream(new File(romFileName));
   }
   rom = new byte[0x04000];

   int total = 0x04000;
   do {
    total -= is.read(rom, 0x04000 - total, total);      // Read the first bank (bank 0)
   } while (total > 0);

   cartType = rom[0x0147];

   numBanks = lookUpCartSize(rom[0x0148]);   // Determine the number of 16kb rom banks

   is.close();
   is = new FileInputStream(new File(romFileName));

   rom = new byte[0x04000 * numBanks];   // Recreate the ROM array with the correct size
   total = 0x04000 * numBanks;           // Calculate total ROM size
   do {                                  // Read ROM into memory
    total -= is.read(rom, rom.length - total, total); // Read the entire ROM
   } while (total > 0);
   is.close();

   JavaBoy.debugLog("Loaded ROM '" + romFileName + "'.  " + numBanks + " banks, " + (numBanks * 16) + "Kb.");
   JavaBoy.debugLog("Type: " + cartTypeTable[cartType]);

   if (!verifyChecksum() && (a instanceof Frame)) {
    new ModalDialog((Frame) a, "Warning", "This cartridge has an invalid checksum.", "It may not execute correctly.");
   }

   loadBatteryRam();
   cartridgeReady = true;

  } catch (IOException e) {
   System.out.println("Error opening ROM image '" + romFileName + "'!");
  } catch (IndexOutOfBoundsException e) {
   new ModalDialog((Frame) a, "Error",
     "Loading the ROM image failed.",
     "The file is not a valid Gameboy ROM.");
  }

 }

 public Cartridge(URL documentBase, String romFileName, Component a) {
  applet = a; /* 5823 */
  this.romFileName = romFileName;
  InputStream is = null;
  try {
   is = new URL(documentBase, romFileName).openStream();
   rom = new byte[0x04000];

   int total = 0x04000;
   do {
    total -= is.read(rom, 0x04000 - total, total);      // Read the first bank (bank 0)
   } while (total > 0);

   cartType = rom[0x0147];
   numBanks = lookUpCartSize(rom[0x0148]);   // Determine the number of 16kb rom banks

   is.close();
   is = new URL(documentBase, romFileName).openStream();

   rom = new byte[0x04000 * numBanks];   // Recreate the ROM array with the correct size
   total = 0x04000 * numBanks;           // Calculate total ROM size
   do {                                  // Read ROM into memory
    total -= is.read(rom, rom.length - total, total); // Read the entire ROM
   } while (total > 0);
   is.close();

   JavaBoy.debugLog("Loaded ROM '" + romFileName + "'.  " + numBanks + " banks, " + (numBanks * 16) + "Kb.");
   JavaBoy.debugLog("Type: " + cartTypeTable[cartType]);

   loadBatteryRam();

   cartridgeReady = true;

  } catch (IOException e) {
   System.out.println("Error opening ROM image '" + romFileName + "'!");
  } catch (IndexOutOfBoundsException e) {
   new ModalDialog((Frame) a, "Error",
     "Loading the ROM image failed.",
     "The file is not a valid Gameboy ROM.");
  }

 }

 /** Returns the byte currently mapped to a CPU address.  Addr must be in the range 0x0000 - 0x4000 or
  *  0xA000 - 0xB000 (for RAM access)
  */
 public final byte addressRead(int addr) {
//  if (disposed) System.out.println("oh.  dodgy cartridge");

//  if (cartType == 0) {
//   return (byte) (rom[addr] & 0x00FF);
//  } else {
   if ((addr >= 0xA000) && (addr <= 0xBFFF)) {
    return ram[addr - 0xA000 + ramPageStart];
   } if (addr < 0x4000) {
    return (byte) (rom[addr]);
   } else {
    return (byte) (rom[pageStart + addr - 0x4000]);
   }
//  }
 }


 /** Returns a string summary of the current mapper status */
 public String getMapInfo() {
  String out;
  switch (cartType) {
   case 0 /* No mapper */ :
   case 8 :
   case 9 :
     return "This ROM has no mapper.";
   case 1 /* MBC1      */ :
     return "MBC1: ROM bank " + JavaBoy.hexByte(currentBank) + " mapped to " +
            " 4000 - 7FFFF";
   case 2 /* MBC1+RAM  */ :
   case 3 /* MBC1+RAM+BATTERY */ :
     out = "MBC1: ROM bank " + JavaBoy.hexByte(currentBank) + " mapped to " +
           " 4000 - 7FFFF.  ";
     if (mbc1LargeRamMode) {
      out = out + "Cartridge is in 16MBit ROM/8KByte RAM Mode.";
     } else {
      out = out + "Cartridge is in 4MBit ROM/32KByte RAM Mode.";
     }
     return out;
   case 5 :
   case 6 : 
    return "MBC2: ROM bank " + JavaBoy.hexByte(currentBank) + " mapped to 4000 - 7FFF";

   case 0x19 :
   case 0x1C :
    return "MBC5: ROM bank " + JavaBoy.hexByte(currentBank) + " mapped to 4000 - 7FFF";

   case 0x1A :
   case 0x1B :
   case 0x1D :
   case 0x1E :
    return "MBC5: ROM bank " + JavaBoy.hexByte(currentBank) + " mapped to 4000 - 7FFF";

  }
  return "Unknown mapper.";
 }

 /** Maps a ROM bank into the CPU address space at 0x4000 */
 public void mapRom(int bankNo) {
//  addressWrite(0x2000, bank);
//  if (bankNo == 0) bankNo = 1;
  currentBank = bankNo;
  pageStart = 0x4000 * bankNo;
 }

 public void reset() {
  mapRom(1);
 }

 /** Save the current mapper state */
 public void saveMapping() {
  if ((cartType != 0) && (savedBank == -1)) savedBank = currentBank;
 }

 /** Restore the saved mapper state */
 public void restoreMapping() {
  if (savedBank != -1) {
   System.out.println("- ROM Mapping restored to bank " + JavaBoy.hexByte(savedBank));
   addressWrite(0x2000, savedBank);
   savedBank = -1;
  }
 }

 /** Writes a byte to an address in CPU address space.  Identical to addressWrite() except that
  *  writes to ROM do not cause a mapping change, but actually write to the ROM.  This is usefull
  *  for patching parts of code.  Only used by the debugger.
  */
 public void debuggerAddressWrite(int addr, int data) {
  if (cartType == 0) {
   rom[addr] = (byte) data;
  } else {
   if (addr < 0x4000) {
    rom[addr] = (byte) data;
   } else {
    rom[pageStart + addr - 0x4000] = (byte) data;
   }
  }
 }

 /** Writes to an address in CPU address space.  Writes to ROM may cause a mapping change.
  */
 public final void addressWrite(int addr, int data) {
  int ramAddress = 0;


  switch (cartType) {

   case 0 : /* ROM Only */
    break;

   case 1 : /* MBC1 */
   case 2 :
   case 3 :
    if ((addr >= 0xA000) && (addr <= 0xBFFF)) {
     if (ramEnabled) {
      ramAddress = addr - 0xA000 + ramPageStart;
      ram[ramAddress] = (byte) data;
     }
    } if ((addr >= 0x2000) && (addr <= 0x3FFF)) {
     int bankNo = data & 0x1F;
     if (bankNo == 0) bankNo = 1;
     mapRom((currentBank & 0x60) | bankNo);
    } else if ((addr >= 0x6000) && (addr <= 0x7FFF)) {
     if ((data & 1) == 1) {
      mbc1LargeRamMode = true;
//      ram = new byte[0x8000];
     } else {
      mbc1LargeRamMode = false;
//      ram = new byte[0x2000];
     }
    } else if (addr <= 0x1FFF) {
     if ((data & 0x0F) == 0x0A) {
      ramEnabled = true;
     } else {
      ramEnabled = false;
     }
    } else if ((addr <= 0x5FFF) && (addr >= 0x4000)) {
     if (mbc1LargeRamMode) {
      ramBank = (data & 0x03);
      ramPageStart = ramBank * 0x2000;
      System.out.println("RAM bank " + ramBank + " selected!");
     } else {
      mapRom((currentBank & 0x1F) | ((data & 0x03) << 5));
     }
    }
    break;

   case 5 :
   case 6 :
    if ((addr >= 0x2000) && (addr <= 0x3FFF) && ((addr & 0x0100) != 0) ) {
     int bankNo = data & 0x1F;
     if (bankNo == 0) bankNo = 1;
     mapRom(bankNo);
    }
    if ((addr >= 0xA000) && (addr <= 0xBFFF)) {
     if (ramEnabled) ram[addr - 0xA000 + ramPageStart] = (byte) data;
    }

    break;

   case 0x19 :
   case 0x1A :
   case 0x1B :
   case 0x1C :
   case 0x1D :
   case 0x1E :

    if ((addr >= 0x2000) && (addr <= 0x2FFF)) {
     int bankNo = (currentBank & 0xFF00) | data;
     mapRom(bankNo);
    }
    if ((addr >= 0x3000) && (addr <= 0x3FFF)) {
     int bankNo = (currentBank & 0x00FF) | ((data & 0x01) << 8);
     mapRom(bankNo);
    }

    if ((addr >= 0x4000) && (addr <= 0x5FFF)) {
     ramBank = (data & 0x07);
     ramPageStart = ramBank * 0x2000;
//     System.out.println("RAM bank " + ramBank + " selected!");
    }
    if ((addr >= 0xA000) && (addr <= 0xBFFF)) {
     ram[addr - 0xA000 + ramPageStart] = (byte) data;
    }
    break;


  }

 }

 /** Read an image of battery RAM into memory if the current cartridge mapper supports it.
  *  The filename is the same as the ROM filename, but with a .SAV extention.
# *  Files are compatible with VGB-DOS.
  */
 public void loadBatteryRam() {
  String saveRamFileName = romFileName;
  int numRamBanks;

  try {
   int dotPosition = romFileName.lastIndexOf('.');

   if (dotPosition != -1) {
    saveRamFileName = romFileName.substring(0, dotPosition) + ".sav";
   } else {
    saveRamFileName = romFileName + ".sav";
   }

   if (rom[0x149] == 0x03) {
    numRamBanks = 4;
   } else {
    numRamBanks = 1;
   }

   if ((cartType == 3) || (cartType == 9) || (cartType == 0x1B) || (cartType == 0x1E) ) {
    FileInputStream is = new FileInputStream(new File(saveRamFileName));
    is.read(ram, 0, numRamBanks * 8192);
    is.close();
    System.out.println("Read SRAM from '" + saveRamFileName + "'");
   }
   if (cartType == 6) {
    FileInputStream is = new FileInputStream(new File(saveRamFileName));
    is.read(ram, 0, 512);
    is.close();
    System.out.println("Read SRAM from '" + saveRamFileName + "'");
   }


  } catch (IOException e) {
   System.out.println("Error loading battery RAM from '" + saveRamFileName + "'");
  }
 }

 /** Writes an image of battery RAM to disk, if the current cartridge mapper supports it. */
 public void saveBatteryRam() {
  String saveRamFileName = romFileName;
  int numRamBanks;

  if (rom[0x149] == 0x03) {
   numRamBanks = 4;
  } else {
   numRamBanks = 1;
  }

  try {
   int dotPosition = romFileName.lastIndexOf('.');

   if (dotPosition != -1) {
    saveRamFileName = romFileName.substring(0, dotPosition) + ".sav";
   } else {
    saveRamFileName = romFileName + ".sav";
   }

   if ((cartType == 3) || (cartType == 9) || (cartType == 0x1B) || (cartType == 0x1E) ) {
    FileOutputStream os = new FileOutputStream(new File(saveRamFileName));
    os.write(ram, 0, numRamBanks * 8192);
    os.close();
    System.out.println("Written SRAM to '" + saveRamFileName + "'");
   }
   if (cartType == 6) {
    FileOutputStream os = new FileOutputStream(new File(saveRamFileName));
    os.write(ram, 0, 512);
    os.close();
    System.out.println("Written SRAM to '" + saveRamFileName + "'");
   }

  } catch (IOException e) {
   System.out.println("Error saving battery RAM to '" + saveRamFileName + "'");
  }
 }

 /** Peforms saving of the battery RAM before the object is discarded */
 public void dispose() {
  saveBatteryRam();
  disposed = true;
 }

 public boolean verifyChecksum() {
  int checkSum = (JavaBoy.unsign(rom[0x14E]) << 8) + JavaBoy.unsign(rom[0x14F]);

  int total = 0;                   // Calculate ROM checksum
  for (int r=0; r < rom.length; r++) {
   if ((r != 0x14E) && (r != 0x14F)) {
    total = (total + JavaBoy.unsign(rom[r])) & 0x0000FFFF;
   }
  }

  return checkSum == total;
 }

 /** Outputs information about the loaded cartridge to stdout. */
 public void outputCartInfo() {
  boolean checksumOk;

  String cartName = new String(rom, 0x0134, 16);
                        // Extract the game name from the cartridge header
  
//  JavaBoy.debugLog(rom[0x14F]+ " "+ rom[0x14E]);


  checksumOk = verifyChecksum();

  String infoString = "ROM Info: Name = " + cartName +
                      ", Size = " + (numBanks * 128) + "Kbit, ";

  if (checksumOk) {
   infoString = infoString + "Checksum Ok.";
  } else {
   infoString = infoString + "Checksum invalid!";
  }

  JavaBoy.debugLog(infoString);
 }


 /** Returns the number of 16Kb banks in a cartridge from the header size byte. */
 public int lookUpCartSize(int sizeByte) {
  int i = 0;
  while ((i < romSizeTable.length) && (romSizeTable[i][0] != sizeByte)) {
   i++;
  }

  if (romSizeTable[i][0] == sizeByte) {
   return romSizeTable[i][1];
  } else {
   return -1;
  }
 }

}