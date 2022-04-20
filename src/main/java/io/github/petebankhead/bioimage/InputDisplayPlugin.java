package io.github.petebankhead.bioimage;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.KeyEventPostProcessor;
import java.awt.KeyboardFocusManager;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;

import ij.IJ;
import ij.ImageJ;
import ij.Menus;
import ij.Prefs;
import ij.plugin.PlugIn;

/**
 * ImageJ plugin to display keypresses.
 * Intended to support live demos and videos.
 * 
 * @author Pete Bankhead.
 */
public class InputDisplayPlugin implements PlugIn {
	

    public static void main(String[] args) {
    	ImageJ ij = new ImageJ(ImageJ.STANDALONE);
    	ij.exitWhenQuitting(true);
    	ij.setVisible(true);
    	Menus.installPlugin(InputDisplayPlugin.class.getName(), Menus.PLUGINS_MENU, "Show keypresses", "", ij);
    	IJ.run("Show keypresses");
    }
    
    
    /**
     * Helper enum for resizing a stage that lacks decorations.
     */
    static enum ResizeDirection {
    	
    	NONE, N, S, E, W, NE, NW, SE, SW;
    	
    	ResizeDirection north() {
    		switch(this) {
    		case NONE: return N;
    		case E: return NE;
    		case W: return NW;
    		default: throw new IllegalArgumentException("Unable to combine " + this + " and " + N);
    		}
    	}
    	
    	ResizeDirection south() {
    		switch(this) {
    		case NONE: return S;
    		case E: return SE;
    		case W: return SW;
    		default: throw new IllegalArgumentException("Unable to combine " + this + " and " + S);
    		}
    	}
    	
    	ResizeDirection west() {
    		switch(this) {
    		case NONE: return W;
    		case N: return NW;
    		case S: return SW;
    		default: throw new IllegalArgumentException("Unable to combine " + this + " and " + W);
    		}
    	}
    	
    	ResizeDirection east() {
    		switch(this) {
    		case NONE: return E;
    		case N: return NE;
    		case S: return SE;
    		default: throw new IllegalArgumentException("Unable to combine " + this + " and " + E);
    		}
    	}
    	
    	void updateBounds(Rectangle bounds, int dx, int dy) {
    		
    		// Handle horizontal
    		switch(this) {
    		case W:
    		case NW:
    		case SW:
    			bounds.x += dx;
    			dx = -dx;
    		case E:
    		case NE:
    		case SE:
    			bounds.width += dx;
    		default:
    			break;
    		}
    		
    		// Handle vertical
    		switch(this) {
    		case N:
    		case NW:
    		case NE:
    			bounds.y += dy;
    			dy = -dy;
    		case S:
    		case SW:
    		case SE:
    			bounds.height += dy;
    		default:
    			break;
    		}
    		
    	}
    	
    	void pickPoint(Rectangle bounds, Point p) {
    		switch(this) {
    		case E:
    		case SE:
    		case NE:
    			p.x = bounds.width;
    			break;
    		case W:
    		case SW:
    		case NW:
    			p.x = 0;
    			break;
    		default:
    			break;
    		}
    		
    		switch(this) {
    		case N:
    		case NE:
    		case NW:
    			p.y = 0;
    			break;
    		case S:
    		case SE:
    		case SW:
    			p.y = bounds.height;
    			break;
    		default:
    			break;
    		}
    	}
    	
    	Cursor getCursor() {
    		switch(this) {
			case E:
				return Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR);
			case N:
				return Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR);
			case NE:
				return Cursor.getPredefinedCursor(Cursor.NE_RESIZE_CURSOR);
			case NW:
				return Cursor.getPredefinedCursor(Cursor.NW_RESIZE_CURSOR);
			case S:
				return Cursor.getPredefinedCursor(Cursor.S_RESIZE_CURSOR);
			case SE:
				return Cursor.getPredefinedCursor(Cursor.SE_RESIZE_CURSOR);
			case SW:
				return Cursor.getPredefinedCursor(Cursor.SW_RESIZE_CURSOR);
			case W:
				return Cursor.getPredefinedCursor(Cursor.W_RESIZE_CURSOR);
			case NONE:
			default:
				return Cursor.getPredefinedCursor(Cursor.HAND_CURSOR);
    		}
    	}
    	
    }
    
    /**
     * Listener for mouse events on a stage without decorations.
     */
    static class KeyEventMouseListener extends MouseAdapter {
        
        private JFrame frame;
        private Point p;
        private Point pFrame;
        private Rectangle bounds;
        private int resizeDistance = 10;
        
        private ResizeDirection resize = ResizeDirection.NONE;

        KeyEventMouseListener(JFrame frame) {
            super();
            this.frame = frame;
        }
        
        @Override
        public void mouseClicked(MouseEvent e) {
            super.mouseClicked(e);
            if (e.getClickCount() > 1)
                frame.dispatchEvent(new WindowEvent(frame, WindowEvent.WINDOW_CLOSING));
        }
        
        @Override
        public void mouseEntered(MouseEvent e) {
        	frame.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }
        
        @Override
        public void mouseMoved(MouseEvent e) {
        	updateResizing(e);
        	frame.setCursor(resize.getCursor());
        }

        @Override
        public void mouseExited(MouseEvent e) {
//        	resetResizing();
        	frame.setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
        }

        @Override
        public void mousePressed(MouseEvent e) {
            super.mousePressed(e);
            p = e.getPoint();
            updateResizing(e);
        }
        
        private void updateResizing(MouseEvent e) {
        	int x = e.getX();
        	int y = e.getY();
        	int width = frame.getWidth();
        	int height = frame.getHeight();
        	
        	ResizeDirection direction = ResizeDirection.NONE;
        	if (x >= 0 && x < resizeDistance && x <= width/2.0) {
        		direction = direction.west();
        	} else if (x < width && width - x < resizeDistance) {
        		direction = direction.east();
        	}

        	if (y >= 0 && y < resizeDistance && e.getY() <= height/2.0) {
        		direction = direction.north();
        	} else if (y < height && height - y < resizeDistance) {
        		direction = direction.south();        		
        	}
        	this.resize = direction;
        }

        @Override
        public void mouseReleased(MouseEvent e) {
            super.mouseReleased(e);
            p = null;
        }

        @Override
        public void mouseDragged(MouseEvent e) {
            super.mouseDragged(e);
            if (p == null)
                return; // Shouldn't happen
            Point pNew = e.getPoint();
            int dx = pNew.x - p.x;
            int dy = pNew.y - p.y;
            if (resize == ResizeDirection.NONE) {
	            pFrame = frame.getLocation(pFrame);
	            pFrame.translate(dx, dy);
	            frame.setLocation(pFrame);            	            	
            } else {
            	bounds = frame.getBounds(bounds);
            	resize.updateBounds(bounds, dx, dy);
            	frame.setBounds(bounds);
            	bounds = frame.getBounds(bounds);
            	resize.pickPoint(bounds, p);
            }
        }
    }

    /**
     * KeyEvent processor to log when keys are pressed somewhere.
     */
    static class KeyEventLogger implements KeyEventPostProcessor {
        
    	private String separator = " ";
        private JLabel label;
        private Set<String> keys = new TreeSet<>();

        KeyEventLogger(JLabel label) {
            this.label = label;
        }

        @Override
        public boolean postProcessKeyEvent(KeyEvent event) {
            String eventText = KeyEvent.getKeyText(event.getKeyCode());
            if (event.getID() == KeyEvent.KEY_RELEASED) {
                keys.remove(eventText);
            } else if (event.getID() == KeyEvent.KEY_PRESSED) {
                if (eventText != null && !eventText.isEmpty())
                    keys.add(eventText);
            }
            
            // By default, we get modifiers at the end... but they look more sensible at the beginning
            StringBuilder sb = new StringBuilder();
            for (String k : keys) {
                if (!isAlphanumeric(k)) {
                	if (sb.length() != 0)
                		sb.append(separator);
                	sb.append(k);
                }
            }
            for (String k : keys) {
                if (isAlphanumeric(k)) {
                	if (sb.length() != 0)
                		sb.append(separator);
                	sb.append(k);
                }
            }
            String s = sb.toString();
            this.label.setText(s);
            return false;
        }
        
        private static Pattern pattern = Pattern.compile("[a-zA-Z0-9]+");
        
        private static boolean isAlphanumeric(String key) {
        	return pattern.matcher(key).matches();
        }
        
    }
    
    
    private static String PREFS_KEY = InputDisplayPlugin.class.getCanonicalName();
    
    private static JFrame frame;
    
    private int x = 25;
    private int y = 25;
    private int width = 200;
    private int height = 100;
    private int arc = 50;
    private float fontSize = 24f;
    private float opacity = 0.75f;

	@Override
	public void run(String arg) {
		if (frame == null) {
			KeyboardFocusManager manager = KeyboardFocusManager.getCurrentKeyboardFocusManager();
	        frame = new JFrame();
	        frame.setUndecorated(true);
	        frame.setBackground(new Color(0, 0, 0, 0));
	        	        
	        // Press spacebar to load from defaults
	        if (!IJ.spaceBarDown()) {
	        	loadPrefs();  	
	        }
	
	        JPanel container = new RoundedJPanel(arc);
	        container.setOpaque(false);
	        container.setLayout(new BorderLayout());
	        frame.setContentPane(container);
	        frame.setAlwaysOnTop(true);
	
	        JLabel label = new JLabel();
	        label.setHorizontalAlignment(JLabel.CENTER);
	        label.setForeground(Color.WHITE);
	        label.setFont(label.getFont().deriveFont(fontSize));
	        container.add(label, BorderLayout.CENTER);
	        
	        KeyEventLogger logger = new KeyEventLogger(label);
	        manager.addKeyEventPostProcessor(logger);
	
	        KeyEventMouseListener mouseListener = new KeyEventMouseListener(frame);
	        frame.addMouseMotionListener(mouseListener);
	        frame.addMouseListener(mouseListener);
	        
	        frame.addWindowListener(new WindowAdapter() {
	            @Override
	            public void windowClosing(WindowEvent e) {
	                super.windowClosing(e);
	                manager.removeKeyEventPostProcessor(logger);
	                savePrefs();
	                frame = null;
	            }
	        });
	
	        frame.setMinimumSize(new Dimension(50, 50));
	        frame.setSize(width, height);
	        frame.setLocation(x, y);
	        frame.setVisible(true);
		} else {
            frame.dispatchEvent(new WindowEvent(frame, WindowEvent.WINDOW_CLOSING));
            frame = null;
		}
	}
	
	
	private void loadPrefs() {
        x = (int)Prefs.get(PREFS_KEY + ".x", x);
        y = (int)Prefs.get(PREFS_KEY + ".y", y);
        width = (int)Prefs.get(PREFS_KEY + ".width", width);
        height = (int)Prefs.get(PREFS_KEY + ".height", height);
        fontSize = (float)Prefs.get(PREFS_KEY + ".fontSize", fontSize);
        opacity = (float)Prefs.get(PREFS_KEY + ".opacity", opacity);
        arc = (int)Prefs.get(PREFS_KEY + ".arc", arc);        	
	}
	
	private void savePrefs() {
		if (frame != null) {
			x = frame.getX();
			y = frame.getY();
			width = frame.getWidth();
			height = frame.getHeight();
		}
        Prefs.set(PREFS_KEY + ".x", x);
        Prefs.set(PREFS_KEY + ".y", y);
        Prefs.set(PREFS_KEY + ".width", width);
        Prefs.set(PREFS_KEY + ".height", height);
        Prefs.set(PREFS_KEY + ".fontSize", fontSize);
        Prefs.set(PREFS_KEY + ".opacity", opacity);
        Prefs.set(PREFS_KEY + ".arc", arc); 
        Prefs.savePreferences();
	}

	
	/**
	 * A JPanel with rounded corners.
	 */
	static class RoundedJPanel extends JPanel {
		
		private static final long serialVersionUID = -4012923773074231024L;
		
		private int arc;
		
		RoundedJPanel(int arc) {
			super();
			this.arc = arc;
			setBackground(new Color(0, 0, 0, 200));
		}
		
		public int getArc() {
			return arc;
		}
		
		public void setArc(int arc) {
			this.arc = arc;
			invalidate();
		}

		@Override
		public void paintComponent(Graphics g) {
			super.paintComponent(g);
			g = g.create();
			g.setColor(getBackground());
			if (g instanceof Graphics2D) {
				((Graphics2D) g).setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			}
			g.fillRoundRect(0, 0, getWidth(), getHeight(), arc, arc);
			g.dispose();
		}
		
		
	}
	
	
    
}
