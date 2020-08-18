package tech.mistermel.easierbackup;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

public class EasierBackup extends JavaPlugin {

	private static EasierBackup instance;

	private File serverFolder;
	private File backupsFolder;

	private SimpleDateFormat dateFormat;
	private int compressionLevel, maxBackupSize;

	private List<File> exemptFiles = new ArrayList<>();

	private boolean isRunning;

	@Override
	public void onEnable() {
		instance = this;

		File configFile = new File(this.getDataFolder(), "config.yml");
		if (!configFile.exists()) {
			this.saveDefaultConfig();
		}

		this.serverFolder = this.getServer().getWorldContainer();
		this.backupsFolder = new File(serverFolder, "backups");
		if (!backupsFolder.isDirectory()) {
			backupsFolder.mkdir();
		}

		this.dateFormat = new SimpleDateFormat(this.getConfig().getString("date-format"));
		for (String fileName : this.getConfig().getStringList("exempt")) {
			File file = new File(serverFolder, fileName);
			exemptFiles.add(file);
		}

		this.compressionLevel = this.getConfig().getInt("compression-level");
		if (compressionLevel > 9) {
			compressionLevel = 9;
			this.getLogger().warning("Compression level cannot be set higher than 9. Defaulting to 9.");
		}
		
		this.maxBackupSize = this.getConfig().getInt("max-backup-folder-size");
		if(maxBackupSize > 0) {
			maxBackupSize *= 1000000000;
		} else {
			this.getLogger().warning("Max backup folder size is disabled. You will need to manually delete old backups.");
		}

		this.getCommand("easierbackup").setExecutor(new CommandHandler());
	}

	public void doBackup() {
		if(isRunning) {
			throw new IllegalStateException("Backup is already running");
		}
		
		this.isRunning = true;
		
		Set<World> autosaveWorlds = new HashSet<>();
		for(World world : Bukkit.getWorlds()) {
			this.getLogger().info("Saving " + world.getName() + "...");
			world.save();
			
			if(world.isAutoSave()) {
				autosaveWorlds.add(world);
			}
			
			world.setAutoSave(false);
		}
		this.getLogger().info("Creating ZIP file, please wait...");

		String fileName = this.getConfig().getString("file-name").replace("%%date%%", dateFormat.format(new Date()));
		File zipFile = new File(backupsFolder, fileName);

		try {
			zipFile.createNewFile();
		} catch (IOException e) {
			this.getLogger().log(Level.SEVERE, "Error occurred while attempting to create backup file", e);
			this.isRunning = false;
			return;
		}

		this.getServer().getScheduler().runTaskAsynchronously(this, () -> {
			try {
				this.zipFolder(serverFolder, zipFile);
			} catch (IOException e) {
				this.getLogger().log(Level.SEVERE, "Error occurred while attempting to create zip file", e);
				this.isRunning = false;
				return;
			}
			
			this.getLogger().info("ZIP file created (" + readableFileSize(zipFile.length()) + ")");
			
			for(World world : autosaveWorlds) {
				world.setAutoSave(true);
			}
			this.getLogger().info("Re-enabled autosave for " + autosaveWorlds.size() + (autosaveWorlds.size() == 1 ? " world" : " worlds"));
			
			this.isRunning = false;
		});
	}

	private void zipFolder(File srcFolder, File destFile) throws IOException {
		ZipOutputStream zipOut = new ZipOutputStream(new FileOutputStream(destFile));
		zipOut.setLevel(compressionLevel);

		this.addFolderToZip(srcFolder, "", zipOut);

		zipOut.flush();
		zipOut.close();
	}

	private void addFolderToZip(File folder, String path, ZipOutputStream zipOut) {
		if(exemptFiles.contains(folder)) {
			return;
		}

		for(File file : folder.listFiles()) {
			String filePath = (path.isEmpty() ? "" : path + "/") + file.getName();

			if (file.isDirectory()) {
				this.addFolderToZip(file, filePath, zipOut);
				continue;
			}

			this.addFileToZip(file, filePath, zipOut);
		}
	}

	private void addFileToZip(File file, String path, ZipOutputStream zipOut) {
		if(file.getName().equals("session.lock") || exemptFiles.contains(file)) {
			return;
		}

		try {
			FileInputStream fileIn = new FileInputStream(file);
			zipOut.putNextEntry(new ZipEntry(path));

			byte[] bytes = new byte[1024];
			int length;
			while ((length = fileIn.read(bytes)) >= 0) {
				zipOut.write(bytes, 0, length);
			}

			zipOut.closeEntry();
			fileIn.close();
		} catch (IOException e) {
			this.getLogger().log(Level.SEVERE, "Error occurred while attempting to add file to zip (" + file.getName() + ")", e);
		}
	}

	public static String readableFileSize(long size) {
	    if(size <= 0)
	    	return "0 B";
	    
	    final String[] units = new String[] { "B", "kB", "MB", "GB", "TB" };
	    int digitGroups = (int) (Math.log10(size) / Math.log10(1024));
	    return new DecimalFormat("#.##").format(size / Math.pow(1024, digitGroups)) + " " + units[digitGroups];
	}
	
	public boolean isRunning() {
		return isRunning;
	}

	public static EasierBackup instance() {
		return instance;
	}

}
