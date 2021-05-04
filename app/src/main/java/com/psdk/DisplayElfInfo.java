package com.psdk;

import java.io.File;
import java.util.HashMap;

public class DisplayElfInfo {

	private final static String ARMV7ABI = "armeabi-v7a";
	private final static String X86 = "x86";
	private final static String MIPS = "mips";
	private final static String X86_64 = "x86_64";
	private final static String ARM64_V8 = "arm64-v8a";
	private final static String ARMABI = "armeabi";

	private static HashMap<Integer, String> typeMap = new HashMap<>();

	public static String findAbiType(File libFile) {
		if (!libFile.exists()) {
			return "";
		}

		if (typeMap.isEmpty()) {
			initializeMap();
		}

		try {
			ReadElf elf = ReadElf.read(libFile);
			if (elf.getType() == 3) {
				int archCode = elf.getArch();
				elf.close();
				return typeMap.get(archCode);
			}
			elf.close();
		} catch (Exception e) {
			e.printStackTrace();
		}
		return "";
	}

	private static void initializeMap() {
		typeMap.put(40, ARMV7ABI);
		typeMap.put(3, X86);
		typeMap.put(8, MIPS);
		typeMap.put(62, X86_64);
		typeMap.put(183, ARM64_V8);
		typeMap.put(164, ARMABI);
	}
}
