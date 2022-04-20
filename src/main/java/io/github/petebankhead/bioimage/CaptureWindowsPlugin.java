package io.github.petebankhead.bioimage;

import java.awt.AWTException;
import java.awt.Frame;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.event.WindowFocusListener;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;

import ij.IJ;
import ij.ImageJ;
import ij.ImagePlus;
import ij.Menus;
import ij.Prefs;
import ij.WindowManager;
import ij.plugin.PlugIn;

/**
 * Plugin to help create screenshots of various ImageJ windows efficiently.
 * <p>
 * This was created to help with presentation-making and book-writing, because I was fed up battling the idiosyncrasies of 
 * screenshot software on different platforms.
 * I wanted cropped screenshots saved as PNG quickly with sensible names to a default location, no platform-specific drop shadows, 
 * and no visible desktop.
 * <p>
 * This plugin helps by maked it easy to create a capture of the active window, all windows individually, or a merged scene 
 * containing all ImageJ windows but without the desktop.
 * The directory and filename are set in the dialog, so there is no need to use custom choosers for every image that should be saved.
 * 
 * @author Pete Bankhead
 */
public class CaptureWindowsPlugin implements PlugIn {

	private static String TITLE = "Capture window screenshots";

	/**
	 * For testing plugin within ImageJ.
	 * @param args
	 */
	public static void main(String[] args) {
		ImageJ imagej = new ImageJ();
		imagej.exitWhenQuitting(true);

		Menus.installPlugin(CaptureWindowsPlugin.class.getName(), Menus.PLUGINS_MENU, TITLE, "", imagej);

		imagej.setVisible(true);
		IJ.run("Blobs");
		IJ.run("Measure");
		IJ.run("Histogram");
		IJ.run("Capture window screenshots");
	}

	private WindowCapturerDialog capturer;


	@Override
	public void run(String arg) {
		if (capturer == null)
			capturer = new WindowCapturerDialog();
		capturer.getFrame().setVisible(true);
	}



	private static class WindowCapturerDialog {

		private static enum CaptureType {
			ALL, ACTIVE, MERGED
		}
		
		private static String PREFS_KEY = WindowCapturerDialog.class.getCanonicalName();

		private JFrame frame;

		/**
		 * Output directory path
		 */
		private JTextField tfPath = new JTextField(24);

		/**
		 * Output base name
		 */
		private JTextField tfName = new JTextField("screenshot");

		/**
		 * Capture delay
		 */
		private SpinnerNumberModel spinnerDelayModel = new SpinnerNumberModel(0, 0, 10, 1);
		private JSpinner spinnerDelay = new JSpinner(spinnerDelayModel);

		/**
		 * Ensure filenames are unique
		 */
		private JCheckBox cbUnique = new JCheckBox("Ensure unique filenames");

		/**
		 * Include toolbar when exporting multiple windows
		 */
		private JCheckBox cbIncludeToolbar = new JCheckBox("Include toolbar");

		/**
		 * Output file extension
		 */
		private String ext = ".png";
		
		private Window previousFocusedWindow;

		private static void addRow(JPanel panel, GridBagConstraints c, String tooltip, JComponent... components) {
			c.gridx = 0;
			for (int i = 0; i < components.length; i++) {
				if (i > 0 && components[i-1] instanceof JLabel)
					((JLabel)components[i-1]).setLabelFor(components[i]);
				panel.add(components[i], c);
				if (tooltip != null)
					components[i].setToolTipText(tooltip);
				c.gridx++;
			}
			c.gridy++;
		}


		JFrame getFrame() {
			if (frame == null)
				initFrame();
			return frame;
		}

		private synchronized void initFrame() {
			if (frame != null) {
				IJ.log("Window capture frame already initialized!");
				return;
			}

			frame = new JFrame(TITLE);

			tfPath.addMouseListener(new MouseAdapter() {
				@Override
				public void mouseClicked(MouseEvent e) {
					if (e.getClickCount() == 2) {
						String dir = IJ.getDirectory("Choose output directory");
						if (dir != null)
							tfPath.setText(dir);
					}
				}
			});

			GridBagLayout layout = new GridBagLayout();
			GridBagConstraints c = new GridBagConstraints();
			JPanel panel = new JPanel(layout);

			c.fill = GridBagConstraints.HORIZONTAL;

			c.gridy = 0;
			addRow(panel, c, "Choose the output directory (double-click to open a directory chooser)", new JLabel("Directory"), tfPath);
			addRow(panel, c, "Choose the base name for the window captures", new JLabel("Name"), tfName);
			addRow(panel, c, "Choose the delay (in seconds) before the capture", new JLabel("Delay"), spinnerDelay);

			c.gridwidth = 2;
			addRow(panel, c, "Include main ImageJ toolbar when capturing windows", cbIncludeToolbar);			
			addRow(panel, c, "Avoid overwriting existing image files by ensuring all filenames are unique", cbUnique);			

			JButton btnActive = new JButton("Active");
			btnActive.setToolTipText("Capture the active image only");
			btnActive.addActionListener(e -> captureDelayed(CaptureType.ACTIVE));

			JButton btnMerge = new JButton("Merged");
			btnMerge.setToolTipText("Capture a merged image containing all windows");
			btnMerge.addActionListener(e -> captureDelayed(CaptureType.MERGED));

			JButton btnAll = new JButton("Individual");
			btnAll.setToolTipText("Capture a separate image of each window individually");
			btnAll.addActionListener(e -> captureDelayed(CaptureType.ALL));

			GridLayout layoutButtons = new GridLayout(1, 3);
			JPanel paneButtons = new JPanel(layoutButtons);
			paneButtons.add(btnActive);
			paneButtons.add(btnAll);
			paneButtons.add(btnMerge);
			addRow(panel, c, null, paneButtons);

			panel.setBorder(new EmptyBorder(10, 10, 10, 10));
			frame.setContentPane(panel);
			frame.setFocusable(false);
			frame.pack();
			
			loadPrefs();
			
			frame.addWindowFocusListener(new WindowFocusListener() {
				
				@Override
				public void windowLostFocus(WindowEvent e) {
					previousFocusedWindow = null;
				}
				
				@Override
				public void windowGainedFocus(WindowEvent e) {
					previousFocusedWindow = e.getOppositeWindow();
				}
			});
			
			frame.addWindowListener(new WindowAdapter() {
				@Override
				public void windowClosing(WindowEvent event) {
					savePrefs();
				}
			});
		}

		private boolean captureDelayed(CaptureType capture) {
			Number seconds = (Number)spinnerDelayModel.getValue();
			String dir = tfPath.getText();
			String name = tfName.getText();
			boolean ensureUnique = cbUnique.isSelected();
			boolean includeToolbar = cbIncludeToolbar.isSelected();
			
			if (seconds != null && seconds.longValue() > 0) {
				if (frame != null)
					frame.setVisible(false);
				ForkJoinPool.commonPool().submit(() -> {
					try {
						for (long toWait = seconds.longValue(); toWait > 0; toWait--) {
							if (IJ.escapePressed()) {
								IJ.resetEscape();
								IJ.showStatus("Screenshot cancelled!");
								return;
							}
							IJ.showStatus("Screenshot in " + toWait + " s... (Escape to cancel)");						
							TimeUnit.SECONDS.sleep(1);
						}
						IJ.showStatus("");
						TimeUnit.MILLISECONDS.sleep(100); // We want the status bar cleared...
						SwingUtilities.invokeLater(() -> capture(capture, dir, name, ext, includeToolbar, ensureUnique));
					} catch (InterruptedException e) {
						IJ.log(e.getLocalizedMessage());
					} finally {
						if (frame != null)
							SwingUtilities.invokeLater(() -> frame.setVisible(true));
					}
				});
				return true;
			} else {
				try {
					frame.setVisible(false);
					if (capture(capture, dir, name, ext, includeToolbar, ensureUnique)) {
						savePrefs();
						return true;
					} else
						return false;
				} finally {
					frame.setVisible(true);
				}
			}
		}
		
		
		private void loadPrefs() {
			tfPath.setText(Prefs.get(PREFS_KEY + ".dir", tfPath.getText()));
			tfName.setText(Prefs.get(PREFS_KEY + ".name", tfName.getText()));
			spinnerDelayModel.setValue(Prefs.get(PREFS_KEY + ".delay", spinnerDelayModel.getNumber().doubleValue()));
			cbUnique.setSelected(Prefs.get(PREFS_KEY + ".ensureUnique", cbUnique.isSelected()));
			cbIncludeToolbar.setSelected(Prefs.get(PREFS_KEY + ".includeToolbar", cbIncludeToolbar.isSelected()));
			if (frame != null && !Prefs.doNotSaveWindowLocations) {
				Point location = Prefs.getLocation(PREFS_KEY + ".location");
				if (location != null)
					frame.setLocation(location);
			}
		}
		
		private void savePrefs() {
			Prefs.set(PREFS_KEY + ".dir", tfPath.getText());
			Prefs.set(PREFS_KEY + ".name", tfName.getText());
			Prefs.set(PREFS_KEY + ".delay", spinnerDelayModel.getNumber().doubleValue());
			Prefs.set(PREFS_KEY + ".ensureUnique", cbUnique.isSelected());
			Prefs.set(PREFS_KEY + ".includeToolbar", cbIncludeToolbar.isSelected());
			if (!Prefs.doNotSaveWindowLocations && frame != null && frame.getLocation() != null)
				Prefs.saveLocation(PREFS_KEY + ".location", frame.getLocation());
			Prefs.savePreferences();
		}
		

		private boolean capture(CaptureType capture, String dir, String name, String ext, boolean includeToolbar, boolean ensureUnique) {
			
			switch(capture) {
			case ACTIVE:
				if (previousFocusedWindow != null) {
					// Try to focus the window that was in focus at the time the command was called
					if (previousFocusedWindow != null)
						previousFocusedWindow.requestFocus();
					try {
						return saveWindows(dir, name, ext, ensureUnique, previousFocusedWindow);
					} catch (Exception e) {
						IJ.log("Exception saving active window: " + e.getLocalizedMessage());
					}
				} else
					return saveActiveWindow(dir, name, ext, ensureUnique);
			case ALL:
				return saveAllWindows(dir, name, ext, includeToolbar, ensureUnique);
			case MERGED:
				return saveMergedWindows(dir, name, ext, includeToolbar, ensureUnique);
			default:
				IJ.log("Unknown capture type! " + capture);
				return false;
			}
		}

	}


	private static boolean saveActiveWindow(String dir, String name, String ext, boolean ensureUnique)  {
		Window window = getActiveWindow();
		if (window == null) {
			IJ.log("Cannot create screenshot - no active window found!");
			return false;
		}
		try {
			return saveWindows(dir, name, ext, ensureUnique, window);
		} catch (Exception e) {
			IJ.log("Exception saving active window: " + e.getLocalizedMessage());
			return false;
		}
	}


	private static boolean saveAllWindows(String dir, String name, String ext, boolean includeToolbar, boolean ensureUnique) {
		File baseDir = new File(dir, name);
		if (ensureUnique)
			baseDir = ensureUnique(baseDir);
		if (!ensureDirExists(baseDir))
			return false;

		try {
			return saveWindows(baseDir.getAbsolutePath(), null, ext, ensureUnique, getWindows(includeToolbar));
		} catch (Exception e) {
			IJ.log("Exception saving all windows: " + e.getLocalizedMessage());
			return false;
		}
	}


	private static boolean saveMergedWindows(String dir, String name, String ext, boolean includeToolbar, boolean ensureUnique) {

		Window[] windows = getWindows(includeToolbar);
		Rectangle bounds = null;
		for (Window window : windows) {
			if (bounds == null)
				bounds = window.getBounds();
			else
				Rectangle.union(bounds, window.getBounds(), bounds);
		}
		BufferedImage imgScreen = new BufferedImage(bounds.width, bounds.height, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g2d = imgScreen.createGraphics();

		try {
			boolean toFront = false;
			Robot robot = new Robot();
			for (Window window : windows) {
				if (!window.isVisible())
					continue;

				Rectangle windowBounds = window.getBounds();
				BufferedImage img = capture(robot, window, toFront);

				g2d.drawImage(img, windowBounds.x-bounds.x, windowBounds.y-bounds.y, null);
			}

			File file = makeFile(new File(dir), name, ext, ensureUnique);
			saveImage(imgScreen, file);
			return true;
		} catch (Exception e) {
			IJ.log("Error saving merged windows: " + e.getLocalizedMessage());
			return false;
		}
	}

	/**
	 * Get all windows, excluding windows associated with this capture plugin.
	 * @return
	 */
	private static Window[] getWindows(boolean includeToolbar) {
		Predicate<Window> filter = w -> !TITLE.equals(getTitle(w)) && w.isVisible();
		if (!includeToolbar)
			filter = filter.and(w -> !(w instanceof ImageJ));
		return Arrays.stream(Window.getWindows()).filter(filter).toArray(Window[]::new);
	}

	/**
	 * Get the currently active window.
	 * @return
	 */
	private static Window getActiveWindow() {

		for (Window window : getWindows(true)) {
			if (TITLE.equals(getTitle(window)))
				continue;
			if (window.isFocused())
				return window;
		}
		
		Window activeWindow = WindowManager.getActiveWindow();
		if (activeWindow != null)
			return activeWindow;

		return null;
	}


	private static boolean ensureDirExists(File dir) {
		if (!dir.exists()) {
			if (!dir.mkdirs()) {
				IJ.log("Unable to make directories for " + dir.getAbsolutePath());
				return false;
			}
		}
		return true;
	}


	private static String getTitle(Window window) {
		if (window instanceof Frame)
			return ((Frame)window).getTitle();
		return window.getName() == null ? "Window" : window.getName();
	}


	private static boolean saveWindows(String dir, String name, String ext, boolean ensureUnique, Window... windows) throws AWTException, IOException {
		if (windows.length == 0)
			return false;

		Robot robot = new Robot();
		boolean toFront = true;

		for (Window window : windows) {
			if (!window.isVisible())
				continue;

			BufferedImage img = capture(robot, window, toFront);

			// Get a name from the window, if we need to
			String windowName = name;
			if (windowName == null) {
				if (window instanceof Frame)
					windowName = ((Frame)window).getTitle();
				else
					windowName = window.getName();
				if (windowName == null)
					windowName = "window";
			}

			File file = new File(dir, windowName + ext);
			if (ensureUnique)
				file = ensureUnique(file);
			saveImage(img, file);
		}
		return true;
	}


	private static File makeFile(File dir, String name, String ext, boolean ensureUnique) {
		if (ext == null)
			ext = "";
		if (ext.length() > 0 && !ext.startsWith("."))
			ext = "." + ext;
		File file = new File(dir, name + ext);
		if (ensureUnique)
			return ensureUnique(file);
		else
			return file;
	}

	private static void saveImage(BufferedImage img, File file) throws IOException {
		// Write PNG with ImageIO, since it may have alpha
		if (file.getName().toLowerCase().endsWith(".png"))
			ImageIO.write(img, "PNG", file);
		else {
			ImagePlus imp = new ImagePlus(file.getName(), img);
			IJ.save(imp, file.getAbsolutePath());
		}
	}


	private static File ensureUnique(File file) {
		if (!file.exists())
			return file;
		File dir = file.getParentFile();
		String name = file.getName();
		int ind = name.lastIndexOf(".");
		String ext = "";
		if (ind >= 0) {
			ext = name.substring(ind);
			name = name.substring(0, ind);
		}
		int i = 0;
		while (file.exists()) {
			i++;
			file = new File(dir, name + "-" + i + ext);
		}
		return file;
	}


	private static BufferedImage capture(Robot robot, Window window, boolean toFront) throws AWTException {
		if (toFront)
			window.toFront();		
		Rectangle windowBounds = window.getBounds();
		BufferedImage img = robot.createScreenCapture(windowBounds);
		return img;
	}


}
