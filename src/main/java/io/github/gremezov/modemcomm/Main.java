/* 	TODO
		-	redesign error reporting mechanisms so that error printing is done on the high level, not in individual functions
				-	implement standard in-program error codes (< 0)
				-	add exit_after checking to logError and exit after JOptionPane returns OK in GUI?
		-	recheck sms sending timeout to match with modem's real timeout, because sometimes messages do get through
			even after modem-comm reports timeout
		-	implement port scanning for modem in GUI
		- 	implement USSD "user reply required" functionality
		-	rewrite the mult-panel initialization and other bulk graphic initialization with arrays and loops

	Bugs
		-	forcing (with sudo) to open port already used for internet communication when running modem port scan
			causes the scan to hang at that port, despite timeouts
		-	if running standalone graphics terminal from command line and "no port selected" error occurs, graphical
			window will close instantly upon OKing the error message but the command line will lag before exiting
				-	most likely caused due to threads spawned by dialog box of logError still running and and not letting
					program die (can be solved by System.exit upon returning from startSerialTerminal (but only if dialog box
					is not on EDT, because being on EDT would mean that startSerialTerminal would return before "OK" is pressed))
				-	can be replicated by having a dialog box being the last thing the program runs and not calling System.exit
		-	running with modem-comm -g -t -p <non-existant-terminal> will not print errors because program will exit in port checking
			logic section before logError will have the chance to display the JOptionPane
*/

package io.github.gremezov.modemcomm;

import com.fazecast.jSerialComm.SerialPort;
import com.fazecast.jSerialComm.SerialPortDataListener;
import com.fazecast.jSerialComm.SerialPortEvent;
import org.apache.commons.cli.*;
import javax.swing.*;
import javax.swing.JOptionPane;
import javax.swing.text.DefaultCaret;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.concurrent.ExecutionException;
import java.util.Scanner;
import java.util.List;

public class Main {

	private static boolean is_gui = false;
	private static boolean is_standalone_gui_terminal = false;
	private static boolean verbose_output = false;

	private static SerialPort modemport = null;	// set to null temporary before initializing it below
	private static int baudrate = 115200;	// default baud rate
	private static String terminal_end_chars = "\r";	// default ending characters to use with the serial terminal

	public static void main(String[] args) {

		CommandLineParser parser = new DefaultParser();
		Options options = new Options();

		options.addOption(Option.builder("u").longOpt("ussd").argName("code").hasArg().desc("send a USSD code").build());
		options.addOption(Option.builder("p").longOpt("port").argName("port-name").hasArg().desc("manually select the modem's serial port").build());
		options.addOption(Option.builder("b").longOpt("baud").argName("baud-rate").hasArg().desc("specify the baud rate").build());
		options.addOption(Option.builder("m").longOpt("sms").argName("phone-number> <message").numberOfArgs(2).desc("send an SMS").build());
		options.addOption(Option.builder("e").longOpt("end-chars").argName("characters").hasArg().desc("ending characters to use with the serial terminal").build());
		options.addOption("a", "auto-select-port", false, "select a modem port by scanning all available serial ports");
		options.addOption("s", "scan", false, "scan all available serial ports for useable modems");
		options.addOption("h", "help", false, "show a help message");
		options.addOption("v", "verbose", false, "print verbose diagnostic messages");
		options.addOption("g", "gui", false, "launch the GUI");
		options.addOption("t", "terminal", false, "launch a serial terminal");

		try{
			CommandLine cmdline = parser.parse(options, args);

			if(cmdline.hasOption("gui") || System.console() == null){
				is_gui = true;
			} else if(System.console() != null && args.length == 0){
				logError("Error: No arguments given. Try -h for help.\n");
				System.exit(1);
			}

			if(cmdline.hasOption("help")){
				HelpFormatter formatter = new HelpFormatter();
				formatter.printHelp("modemcomm", options);
				System.exit(0);
			}
			if(cmdline.hasOption("verbose")){
				verbose_output = true;
			}
			if(cmdline.hasOption("end-chars")){
				terminal_end_chars = cmdline.getOptionValue("end-chars").replace("\\n", "\n").replace("\\r", "\r");
			}
			if(cmdline.hasOption("scan")){
				SerialPort[] modemPorts = scanForModemPorts();
				if(modemPorts.length > 0){
					logVerboseOutput("Found useable modems on the following ports:\n");
					for(SerialPort sp : modemPorts){
						System.out.println(sp.getSystemPortName());
					}
				} else{
					logVerboseOutput("No useable modems found.\n");
				}
				System.exit(0);
			}
			if(cmdline.hasOption("baud")){
				try{
					baudrate = Integer.parseInt(cmdline.getOptionValue("baud"));
				} catch (NumberFormatException e){
					logError("Error: Baud rate must be an integer.\n");
					System.exit(1);
				}
			}

			if(cmdline.hasOption("auto-select-port")){
				if(is_gui){
					SwingUtilities.invokeLater(() -> {
						JOptionPane.showMessageDialog(null, "Scanning serial ports for a modem...", "scan running", JOptionPane.PLAIN_MESSAGE);
					});
				} else{
					logVerboseOutput("Scanning serial ports for a modem...\n");
				}
				SerialPort[] modemPorts = scanForModemPorts();
				if(modemPorts == null){
					logError("Error: No serial ports found.\n");
					System.exit(1);
				} else if(modemPorts.length == 0){
					logError("Error: No modems found.\n");
					System.exit(1);
				}
				modemport = modemPorts[0];
				if(is_gui){
					SwingUtilities.invokeLater(() -> {
						JOptionPane.showMessageDialog(null, "Selecting port "+modemport.getSystemPortName(), "modem selected", JOptionPane.PLAIN_MESSAGE);
					});
				} else{
					logVerboseOutput("Selecting port "+modemport.getSystemPortName()+"\n");
				}
			} else if(cmdline.hasOption("port")){
				String manual_port_name = cmdline.getOptionValue("port");

				// getCommPort only takes the port name not the full path so on linux, for example, ttyUSB0 must be passed instead of /dev/ttyUSB0
				if(manual_port_name.length() >= 5 && manual_port_name.substring(0,5).equals("/dev/")){
					manual_port_name = manual_port_name.substring(5);
				}

				// check if specified port exists
				SerialPort[] allPorts = SerialPort.getCommPorts();
				for(SerialPort p : allPorts){
					if(p.getSystemPortName().equals(manual_port_name)){
						modemport = SerialPort.getCommPort(manual_port_name);
						break;
					}
				}
				if(modemport == null){
					logError("Error: Port "+manual_port_name+" does not exist.\n");
					System.exit(1);
				}
			}

			if(cmdline.hasOption("terminal")){
				if(startSerialTerminal(modemport, baudrate, terminal_end_chars) == -1){
					if(!is_gui) System.exit(1);	// rewrite to exit if error in any case, gui or not. if no error, do nothing
				} else{
					if(!is_gui) System.exit(0);
				}
				is_standalone_gui_terminal = true;
			}
			if(cmdline.hasOption("ussd") && !is_gui){
				String resp = startSendReceiveUSSD(modemport, cmdline.getOptionValue("ussd"));
				if(resp == null){
					System.exit(1);
				}
				System.out.println(resp);
				System.exit(0);
			}
			if(cmdline.hasOption("sms") && !is_gui){
				String[] opts = cmdline.getOptionValues("sms");
				if(opts.length < 2){
					logError("Error: SMS message not given.\n");
					System.exit(1);
				}
				startSendSMS(modemport, opts[0], opts[1]);
				System.exit(0);
			}
		} catch (ParseException e){
			logError("Error: Failed to parse command-line arguments. Reason: "+e.getMessage()+"\n");
		}

		if(is_gui && !is_standalone_gui_terminal){
			SwingUtilities.invokeLater(() -> {
				runGUI();
			});
		}
	}

	// frame and main panel
	private static JFrame frame;
	private static JPanel mainPanel;

	// menu buttons
	private static JPanel menuPanel;
	private static JButton homeMenuButton;
	private static JButton USSDMenuButton;
	private static JButton SMSMenuButton;
	private static JButton terminalMenuButton;

	// Home screen
	private static JPanel homePanel;
	private static JLabel homePortLabel;
	private static JComboBox<String> homePortSelectorComboBox;

	// USSD screen
	private static JPanel USSDPanel;
	private static JPanel USSDPanelBox;
	private static JLabel USSDLabel;
	private static JTextField USSDInputField;
	private static JButton USSDSendButton;
	private static JTextArea USSDReplyArea;
	private static JScrollPane USSDReplyAreaScrollPane;

	// SMS screen
	private static JPanel SMSPanel;
	private static JPanel SMSPanelBox;
	private static JLabel SMSNumberLabel;
	private static JTextField SMSNumberInputField;
	private static JLabel SMSTextAreaLabel;
	private static JTextArea SMSTextArea;
	private static JScrollPane SMSTextAreaScrollPane;
	private static JButton SMSSendButton;

	// terminal screen
	private static JPanel terminalPanel;
	private static JPanel terminalPanelBox;
	private static JLabel terminalPortLabel;
	private static JComboBox<String> terminalPortSelectorComboBox;
	private static JLabel terminalBaudLabel;
	private static JTextField terminalBaudInputField;
	private static JLabel terminalEndcharsLabel;
	private static JTextField terminalEndcharsInputField;
	private static JButton terminalLaunchButton;

	private static void runGUI(){

		// function runGUI
		// Initializes and runs the swing GUI.

		String no_port_selected_str = "- none selected -";

		// get all port names for usage in port selection lists
		SerialPort[] sp = SerialPort.getCommPorts();
		String[] port_names = new String[sp.length+1];
		port_names[0] = no_port_selected_str;	// default option is none
		for(int i = 0; i < sp.length; i++){
			port_names[i+1] = sp[i].getSystemPortName();
		}

		/* Frame */

		frame = new JFrame("modem-comm");
		frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

		/* Home */

		homePanel = new JPanel();
		homePanel.setBackground(Color.LIGHT_GRAY);

		homePortLabel = new JLabel("Modem Port:");
		homePortLabel.setFont(new Font("Arial", Font.BOLD, 14));

		homePortSelectorComboBox = new JComboBox<>(port_names);

		// set the default selected item to the currently used modem port
		if(modemport != null){
			homePortSelectorComboBox.setSelectedItem(modemport.getSystemPortName());
		}

		homePortSelectorComboBox.addActionListener(e -> {
			String selected_port_name = (String)homePortSelectorComboBox.getSelectedItem();
			if(selected_port_name.equals(no_port_selected_str)){
				modemport = null;
			} else {
				modemport = SerialPort.getCommPort(selected_port_name);
			}
		});

		homePanel.add(homePortLabel);
		homePanel.add(homePortSelectorComboBox);

		/* USSD */

		USSDPanel = new JPanel(new BorderLayout());
		USSDPanel.setBackground(Color.LIGHT_GRAY);

		USSDPanelBox = new JPanel();
		USSDPanelBox.setLayout(new BoxLayout(USSDPanelBox, BoxLayout.Y_AXIS));
		USSDPanelBox.setBackground(USSDPanel.getBackground());

		USSDLabel = new JLabel("USSD Code:");
		USSDLabel.setFont(new Font("Arial", Font.BOLD, 14));

		USSDInputField = new JTextField(20);
		USSDInputField.setFont(new Font("Arial", Font.PLAIN, 14));

		USSDSendButton = new JButton("Send");
		USSDSendButton.setFont(new Font("Arial", Font.BOLD, 14));

		USSDReplyArea = new JTextArea(10, 30);
		USSDReplyArea.setFont(new Font("Arial", Font.PLAIN, 14));
		USSDReplyArea.setEditable(false);
		USSDReplyArea.setLineWrap(true);
		USSDReplyArea.setWrapStyleWord(true);

		// make text area auto-scoll down when data overflows
		DefaultCaret USSDReplyAreaCaret = (DefaultCaret) USSDReplyArea.getCaret();
		USSDReplyAreaCaret.setUpdatePolicy(DefaultCaret.ALWAYS_UPDATE);

		USSDReplyAreaScrollPane = new JScrollPane(USSDReplyArea);

		USSDSendButton.addActionListener(e -> {
			String input = USSDInputField.getText();
			if(input.equals("")){
				logError("Error: Please input the USSD code.");
			} else {
				startSendReceiveUSSD(modemport, input);
			}
		});

		JPanel up1 = new JPanel(new FlowLayout(FlowLayout.LEFT));
		up1.add(USSDLabel);
		up1.setBackground(USSDPanel.getBackground());

		JPanel up2 = new JPanel(new FlowLayout(FlowLayout.LEFT));
		up2.add(USSDInputField);
		up2.setBackground(USSDPanel.getBackground());
		up2.add(USSDSendButton);

		JPanel up3 = new JPanel(new FlowLayout(FlowLayout.LEFT));
		up3.add(USSDReplyAreaScrollPane);
		up3.setBackground(USSDPanel.getBackground());

		USSDPanelBox.add(up1);
		USSDPanelBox.add(up2);
		USSDPanelBox.add(up3);

		USSDPanel.add(USSDPanelBox, BorderLayout.PAGE_START);

		/* SMS */

		SMSPanel = new JPanel(new BorderLayout());
		SMSPanel.setBackground(Color.LIGHT_GRAY);

		SMSPanelBox = new JPanel();
		SMSPanelBox.setLayout(new BoxLayout(SMSPanelBox, BoxLayout.Y_AXIS));
		SMSPanelBox.setBackground(SMSPanel.getBackground());

		SMSNumberLabel = new JLabel("Recipient Phone Number:");
		SMSNumberLabel.setFont(new Font("Arial", Font.BOLD, 14));

		SMSNumberInputField = new JTextField(20);
		SMSNumberInputField.setFont(new Font("Arial", Font.PLAIN, 14));

		SMSTextAreaLabel = new JLabel("Message:");
		SMSTextAreaLabel.setFont(new Font("Arial", Font.BOLD, 14));

		SMSTextArea = new JTextArea(10, 30);
		SMSTextArea.setFont(new Font("Arial", Font.PLAIN, 14));
		SMSTextArea.setEditable(true);
		SMSTextArea.setLineWrap(true);
		SMSTextArea.setWrapStyleWord(true);

		SMSTextAreaScrollPane = new JScrollPane(SMSTextArea);

		SMSSendButton = new JButton("Send");
		SMSSendButton.setFont(new Font("Arial", Font.BOLD, 14));

		SMSSendButton.addActionListener(e -> {
			String number = SMSNumberInputField.getText();
			String message = SMSTextArea.getText();
			if(number.equals("")){
				logError("Error: Please input a recipient phone number.");
			} else if(message.equals("")){
				logError("Error: Please input a message.");
			} else{
				startSendSMS(modemport, number, message);
			}
		});

		JPanel sp1 = new JPanel(new FlowLayout(FlowLayout.LEFT));
		sp1.add(SMSNumberLabel);
		sp1.setBackground(SMSPanel.getBackground());

		JPanel sp2 = new JPanel(new FlowLayout(FlowLayout.LEFT));
		sp2.add(SMSNumberInputField);
		sp2.setBackground(SMSPanel.getBackground());

		JPanel sp3 = new JPanel(new FlowLayout(FlowLayout.LEFT));
		sp3.add(SMSTextAreaLabel);
		sp3.setBackground(SMSPanel.getBackground());

		JPanel sp4 = new JPanel(new FlowLayout(FlowLayout.LEFT));
		sp4.add(SMSTextAreaScrollPane);
		sp4.setBackground(SMSPanel.getBackground());

		JPanel sp5 = new JPanel(new FlowLayout(FlowLayout.LEFT));
		sp5.add(SMSSendButton);
		sp5.setBackground(SMSPanel.getBackground());

		SMSPanelBox.add(sp1);
		SMSPanelBox.add(sp2);
		SMSPanelBox.add(sp3);
		SMSPanelBox.add(sp4);
		SMSPanelBox.add(sp5);

		SMSPanel.add(SMSPanelBox, BorderLayout.PAGE_START);

		/* Terminal */

		terminalPanel = new JPanel(new BorderLayout());
		terminalPanel.setBackground(Color.LIGHT_GRAY);

		terminalPanelBox = new JPanel();
		terminalPanelBox.setLayout(new BoxLayout(terminalPanelBox, BoxLayout.Y_AXIS));
		terminalPanelBox.setBackground(terminalPanel.getBackground());

		terminalPortLabel = new JLabel("Serial Port:");
		terminalPortLabel.setFont(new Font("Arial", Font.BOLD, 14));

		terminalPortSelectorComboBox = new JComboBox<>(port_names);

		terminalBaudLabel = new JLabel("Baud Rate:");
		terminalBaudLabel.setFont(new Font("Arial", Font.BOLD, 14));

		terminalBaudInputField = new JTextField(Integer.toString(baudrate), 10);	// set default baud rate to global baud rate
		terminalBaudInputField.setFont(new Font("Arial", Font.PLAIN, 14));

		terminalEndcharsLabel = new JLabel("Ending Character Sequence:");
		terminalEndcharsLabel.setFont(new Font("Arial", Font.BOLD, 14));

		terminalEndcharsInputField = new JTextField(terminal_end_chars.replace("\r", "\\r").replace("\n", "\\n"), 10);
		terminalEndcharsInputField.setFont(new Font("Arial", Font.PLAIN, 14));

		terminalLaunchButton = new JButton("Launch Serial Terminal");
		terminalLaunchButton.setFont(new Font("Arial", Font.BOLD, 14));

		terminalLaunchButton.addActionListener(e -> {
			String selected_port_name = (String)terminalPortSelectorComboBox.getSelectedItem();
			if(selected_port_name.equals(no_port_selected_str)){
				logError("Error: Please select a serial port.");
				return;
			}
			String st_baudrate_str = terminalBaudInputField.getText();
			if(st_baudrate_str.equals("")){
				logError("Error: Please input the baud rate.");
				return;
			}
			int st_baudrate = 0;	// baud rate to use for the serial terminal, could be different from the global baud rate
			try{
				st_baudrate = Integer.parseInt(st_baudrate_str);
			} catch (NumberFormatException exc){
				logError("Error: Baud rate must be an integer.");
				return;
			}
			startSerialTerminal(SerialPort.getCommPort(selected_port_name), st_baudrate, terminalEndcharsInputField.getText().replace("\\r", "\r").replace("\\n", "\n"));
		});

		JPanel tp1 = new JPanel(new FlowLayout(FlowLayout.LEFT));
		tp1.add(terminalPortLabel);
		tp1.setBackground(terminalPanel.getBackground());

		JPanel tp2 = new JPanel(new FlowLayout(FlowLayout.LEFT));
		tp2.add(terminalPortSelectorComboBox);
		tp2.setBackground(terminalPanel.getBackground());

		JPanel tp3 = new JPanel(new FlowLayout(FlowLayout.LEFT));
		tp3.add(terminalBaudLabel);
		tp3.setBackground(terminalPanel.getBackground());

		JPanel tp4 = new JPanel(new FlowLayout(FlowLayout.LEFT));
		tp4.add(terminalBaudInputField);
		tp4.setBackground(terminalPanel.getBackground());

		JPanel tp5 = new JPanel(new FlowLayout(FlowLayout.LEFT));
		tp5.add(terminalEndcharsLabel);
		tp5.setBackground(terminalPanel.getBackground());

		JPanel tp6 = new JPanel(new FlowLayout(FlowLayout.LEFT));
		tp6.add(terminalEndcharsInputField);
		tp6.setBackground(terminalPanel.getBackground());

		JPanel tp7 = new JPanel(new FlowLayout(FlowLayout.LEFT));
		tp7.add(terminalLaunchButton);
		tp7.setBackground(terminalPanel.getBackground());

		terminalPanelBox.add(tp1);
		terminalPanelBox.add(tp2);
		terminalPanelBox.add(tp3);
		terminalPanelBox.add(tp4);
		terminalPanelBox.add(tp5);
		terminalPanelBox.add(tp6);
		terminalPanelBox.add(tp7);

		terminalPanel.add(terminalPanelBox, BorderLayout.PAGE_START);

		/* menu */

		menuPanel = new JPanel();
		menuPanel.setLayout(new FlowLayout(FlowLayout.LEFT));

		homeMenuButton = new JButton("Home");
		homeMenuButton.setFont(new Font("Arial", Font.BOLD, 14));

		homeMenuButton.addActionListener(e -> {
			CardLayout cl = (CardLayout)mainPanel.getLayout();
			cl.show(mainPanel, "homePanel");
		});

		USSDMenuButton = new JButton("USSD");
		USSDMenuButton.setFont(new Font("Arial", Font.BOLD, 14));

		USSDMenuButton.addActionListener(e -> {
			CardLayout cl = (CardLayout)mainPanel.getLayout();
			cl.show(mainPanel, "USSDPanel");
		});

		SMSMenuButton = new JButton("SMS");
		SMSMenuButton.setFont(new Font("Arial", Font.BOLD, 14));

		SMSMenuButton.addActionListener(e -> {
			CardLayout cl = (CardLayout)mainPanel.getLayout();
			cl.show(mainPanel, "SMSPanel");
			SMSSendButton.setText("Send");	// reload the "Send" text on screen reload to prevent it from permanently becomming "Sent" after an SMS is sent
		});

		terminalMenuButton = new JButton("Terminal");
		terminalMenuButton.setFont(new Font("Arial", Font.BOLD, 14));

		terminalMenuButton.addActionListener(e -> {
			CardLayout cl = (CardLayout)mainPanel.getLayout();
			cl.show(mainPanel, "terminalPanel");
		});

		menuPanel.add(homeMenuButton);
		menuPanel.add(USSDMenuButton);
		menuPanel.add(SMSMenuButton);
		menuPanel.add(terminalMenuButton);

		/* main panel */

		mainPanel = new JPanel();
		mainPanel.setLayout(new CardLayout());

		mainPanel.add(homePanel, "homePanel");
		mainPanel.add(USSDPanel, "USSDPanel");
		mainPanel.add(SMSPanel, "SMSPanel");
		mainPanel.add(terminalPanel, "terminalPanel");

		/* Frame final config */

		frame.setLayout(new BorderLayout());
		frame.add(mainPanel, BorderLayout.CENTER);
		frame.add(menuPanel, BorderLayout.NORTH);

		//frame.pack();
		frame.setSize(650,450);
		frame.setLocationRelativeTo(null);	// centers the window
		frame.setVisible(true);
	}

	private static String startSendReceiveUSSD(SerialPort modemport, String ussd_code){

		// function startSendReceiveUSSD
		// GUI mode: Uses SwingWorker to run sendReceiveUSSD in the background and update the UI once the reply is received. Returns null in any case.
		// Terminal mode: Runs sendReceiveUSSD and returns the ussd reply string or null if error.

		if(modemport == null){
			logError("Error: No port selected.\n");
			return null;
		}

		if(is_gui){
			SwingWorker sw1 = new SwingWorker<String, String>(){
				@Override
				protected String doInBackground(){
					publish("Processing...");
					return sendReceiveUSSD(modemport, ussd_code);
				}

				@Override
				protected void process(List<String> chunks){
					USSDSendButton.setText(chunks.get(chunks.size()-1));
					USSDPanel.revalidate();
					USSDPanel.repaint();
				}

				@Override
				protected void done(){
					try{
						String reply = get();
						if(reply == null){
							USSDReplyArea.setText("");
						} else{
							USSDReplyArea.setText(reply);
						}
						USSDSendButton.setText("Send");
						USSDPanel.revalidate();
						USSDPanel.repaint();
					} catch (InterruptedException e){e.printStackTrace();} catch (ExecutionException e){e.printStackTrace();}
				}
			};
			sw1.execute();
			return null;
		} else {
			return sendReceiveUSSD(modemport, ussd_code);
		}
	}

	private static int startSerialTerminal(SerialPort port, int baudrate, String end_chars){

		// function startSerialTerminal
		// Launches a serial terminal, either in the system's terminal or using a GUI.
		// GUI mode: returns -1 if error and 0 if everything ok.
		// Terminal mode: returns -1 if error and never returns (runs in a loop) if everything ok.

		if(port == null){
			logError("Error: No port selected.\n");
			return -1;
		}

		if(!port.openPort()){
			logError("Error: Failed to open port "+port.getSystemPortName()+"\n");
			return -1;
		}

		configPort(port, baudrate);
		PrintStream out = new PrintStream(port.getOutputStream());

		if(is_gui){

			// GUI serial terminal

			// Note: terminalGUI() will be responsible for closing the port and the PrintStream
			if(SwingUtilities.isEventDispatchThread()){
				terminalGUI(port, out, baudrate, end_chars);
			} else {
				SwingUtilities.invokeLater(() -> {
					terminalGUI(port, out, baudrate, end_chars);
				});
			}
		} else{

			// in-terminal serial terminal

			Scanner scanner = new Scanner(System.in);

			System.out.printf("Connection opened to "+port.getSystemPortName()+", baud rate = %d\n", baudrate);

			while(true){
				try{	// need to use try-catch because System.in.available() could throw IO exception
					if(System.in.available() > 0){
						out.print(scanner.nextLine()+end_chars);
						out.flush();
					}
				} catch (IOException e){
					e.printStackTrace();
				}
				if(port.bytesAvailable() > 0){
					System.out.print(ModemUtil.serialRead(port));
				}
			}
			// the loop above can only be exited by ^C'ing the program so closing the stuff below don't matter
			//scanner.close();
			//out.close();
			//port.closePort();
		}
		return 0;
	}

	private static void terminalGUI(SerialPort port, PrintStream out, int baudrate, String end_chars){

		// function terminalGUI
		// Launches the graphical window of the serial terminal.

		JFrame stFrame = new JFrame("Serial Terminal - "+port.getSystemPortName());
		if(is_standalone_gui_terminal) stFrame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

		JPanel stMainPanel = new JPanel();
		stMainPanel.setLayout(new BoxLayout(stMainPanel, BoxLayout.Y_AXIS));

		JTextArea stArea = new JTextArea(10, 30);
		stArea.setFont(new Font("Arial", Font.PLAIN, 14));
		stArea.setEditable(false);
		stArea.setLineWrap(true);
		stArea.setWrapStyleWord(true);

		// make text area auto-scoll down when new data appears
		DefaultCaret stAreaCaret = (DefaultCaret) stArea.getCaret();
		stAreaCaret.setUpdatePolicy(DefaultCaret.ALWAYS_UPDATE);

		/*JButton stClosePortButton = new JButton("Close port");
		stClosePortButton.setFont(new Font("Arial", Font.BOLD, 14));*/

		JScrollPane stAreaScrollPane = new JScrollPane(stArea);

		JTextField stInputField = new JTextField(30);
		stInputField.setFont(new Font("Arial", Font.PLAIN, 14));

		JButton stSendButton = new JButton("Send");
		stSendButton.setFont(new Font("Arial", Font.BOLD, 14));

		stSendButton.addActionListener(e -> {
			String cmd = stInputField.getText();
			out.print(cmd+end_chars);
			out.flush();

			stArea.append(cmd+"\n");
		});

		SwingWorker sw1 = new SwingWorker<Void, Void>(){
			@Override
			protected Void doInBackground(){

				while(!isCancelled()){
					if(port.bytesAvailable() > 0){
						stArea.append(ModemUtil.serialRead(port));
					}
				}
				/*port.addDataListener(new SerialPortDataListener(){
					@Override
					public int getListeningEvents(){
						return SerialPort.LISTENING_EVENT_DATA_AVAILABLE;
					}
					@Override
					public void serialEvent(SerialPortEvent event){
						if(event.getEventType() == SerialPort.LISTENING_EVENT_DATA_AVAILABLE){
							System.out.println(ModemUtil.serialRead(port));
						}
					}
				});

				while(!isCancelled()){
					try{Thread.sleep(1000);}catch(InterruptedException exc){}
				}

				port.removeDataListener();*/

				out.close();
				port.closePort();

				return null;
			}
		};

		stFrame.addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosing(WindowEvent e){
				sw1.cancel(true);
			}
		});

		sw1.execute();

		JPanel stp1 = new JPanel(new BorderLayout());
		stp1.add(stAreaScrollPane, BorderLayout.CENTER);

		JPanel stp2 = new JPanel(new FlowLayout(FlowLayout.LEFT));
		stp2.add(stInputField);
		stp2.add(stSendButton);

		stMainPanel.add(stp1);
		stMainPanel.add(stp2);

		stFrame.add(stMainPanel);

		stFrame.setSize(500, 300);
		stFrame.setLocationRelativeTo(null);
		stFrame.setVisible(true);
	}

	private static Integer startSendSMS(SerialPort modemport, String number, String msg){

		// function startSendSMS
		// GUI mode: Uses SwingWorker to run sendSMS in the background and update the UI once the message has been sent. Returns null in any case.
		// Terminal mode: Runs sendSMS and returns the message reference number. Returns null if encounters an error.

		if(msg.length() > 160){
			logError("Error: SMS message too long (over 160 characters)\n");
			return null;
		}

		String number_nospaces = number.replace(" ", "");

		if(!number_nospaces.matches("^\\+?[0-9]+$")){
			logError("Error: Invalid phone number format.\n");
			return null;
		}

		if(modemport == null){
			logError("Error: No port selected.\n");
			return null;
		}

		if(is_gui){
			SwingWorker sw1 = new SwingWorker<Integer, String>(){
				@Override
				protected Integer doInBackground(){
					publish("Sending...");
					return sendSMS(modemport, number_nospaces, msg);
				}

				@Override
				protected void process(List<String> chunks){
					SMSSendButton.setText(chunks.get(chunks.size()-1));
					SMSPanel.revalidate();
					SMSPanel.repaint();
				}

				@Override
				protected void done(){
					try{
						if(get() != null){
							SMSSendButton.setText("Sent");
						} else {
							SMSSendButton.setText("Send");
						}
						SMSPanel.revalidate();
						SMSPanel.repaint();
					} catch (InterruptedException e){e.printStackTrace();} catch (ExecutionException e){e.printStackTrace();}
				}
			};
			sw1.execute();
			return null;
		} else {
			return sendSMS(modemport, number_nospaces, msg);
		}
	}

	private static void configPort(SerialPort port, int baudrate){

		// function configPort
		// Sets the baud rate and other settings of a serial port.

		port.setBaudRate(baudrate);
		port.setNumDataBits(8);
		port.setNumStopBits(SerialPort.ONE_STOP_BIT);
		port.setParity(SerialPort.NO_PARITY);
	}

	private static void logVerboseOutput(String msg, Object... extra_args){

		// function logVerboseOutput
		// Prints output only if verbose_output is true and if we are not in the GUI.

		if(verbose_output && !is_gui){
			System.out.printf(msg, extra_args);
		}
	}

	private static void logError(String err_msg, Object... extra_args){

		// function logError
		// Prints an error if in terminal and displays error popup if in GUI.

		if(is_gui){
			if(SwingUtilities.isEventDispatchThread()){
				JOptionPane.showMessageDialog(null, String.format(err_msg, extra_args), "Error", JOptionPane.ERROR_MESSAGE);
			} else {
				SwingUtilities.invokeLater(() -> {
					JOptionPane.showMessageDialog(null, String.format(err_msg, extra_args), "Error", JOptionPane.ERROR_MESSAGE);
				});
			}
		} else {
			System.err.printf(err_msg, extra_args);
		}
	}

	public static String checkResponse(SerialPort modemport, String expected_response, int timeout_ms){

		// function checkResponse
		// Returns null if modem reports and error or if timeout occurs before the expected response is detected.
		// If the expected response is detected, then returns the line containing the expected response.
		// Also prints error messages accordingly.

		int exp_resp_len = expected_response.length();
		long cmd_sent_time = System.nanoTime();

		while(true){
			if(modemport.bytesAvailable() > 0){
				String[] input_lines = ModemUtil.serialReadLines(modemport);
				if(input_lines.length > 0){
					for(String s : input_lines){
						if(s.length() >= exp_resp_len && s.substring(0,exp_resp_len).equals(expected_response)){
							return s;
						} else if(s.contains("ERROR")){
							logError("Error: Modem reported an error: "+s+"\n");
							return null;
						}
					}
				}
			}
			if((System.nanoTime()-cmd_sent_time)/1000000 > timeout_ms){
				logError("Error: Modem timeout.\n");
				return null;
			}
		}
	}

	public static String checkResponse(SerialPort modemport, String expected_response){

		// function checkResponse --- (for cases when timeout is not passed) ---
		// Wrapper around checkResponse that passes a default timeout if no timeout is specified.

		int modem_timeout_default_ms = 5000;	// in milliseconds
		return checkResponse(modemport, expected_response, modem_timeout_default_ms);
	}

	private static String sendReceiveUSSD(SerialPort modemport, String ussd_code) {

		// function sendReceiveUSSD
		// Sends a USSD code and then returns the response. Returns null if encounters an error.

		int ussd_timeout_ms = 10000; // in milliseconds
		String reply = null;	// default value to return if error occurs

		if(!modemport.openPort()){
			logError("Error: Failed to open port "+modemport.getSystemPortName()+"\n");
			return null;
		}

		configPort(modemport, baudrate);
		PrintStream out = new PrintStream(modemport.getOutputStream());

		// enable numeric error report
		out.print("AT+CMEE=1\r");
		out.flush();

		String resp = checkResponse(modemport, "OK");
		if(resp == null){
			out.close();
			modemport.closePort();
			return null;
		}

		// set text encoding to "GSM"
		out.print("AT+CSCS=\"GSM\"\r");
		out.flush();

		resp = checkResponse(modemport, "OK");
		if(resp == null){
			out.close();
			modemport.closePort();
			return null;
		}

		logVerboseOutput("Sending USSD...\n");

		// send USSD code
		out.print("AT+CUSD=1,\""+ussd_code+"\",15\r");
		out.flush();

		resp = checkResponse(modemport, "OK");
		if(resp == null){
			out.close();
			modemport.closePort();
			return null;
		}

		logVerboseOutput("Done\n");

		resp = checkResponse(modemport, "+CUSD: ", ussd_timeout_ms);
		if(resp == null){
			out.close();
			modemport.closePort();
			return null;
		}

		String[] reply_items = resp.substring(7).replace("\"", "").split(",");
		// XXX TODO XXX --- Implement checking whether reply from user is needed
		int reply_encoding = Integer.parseInt(reply_items[2]);
		if(reply_encoding == 15){
			reply = ModemUtil.decode7BitGSM(HexFormat.of().parseHex(reply_items[1]));
		} else if (reply_encoding == 72){
			reply = new String(HexFormat.of().parseHex(reply_items[1]), StandardCharsets.UTF_16BE);
		}else{
			logError("Error: USSD response uses an unrecognized encoding format: %d\n", reply_encoding);
		}

		out.close();
		modemport.closePort();
		return reply;
	}

	private static Integer sendSMS(SerialPort modemport, String number, String msg){

		// function send SMS
		// Send an SMS to a specified number and returns the message reference number.
		// Returns null if an error occurs.

		int sms_timeout_ms = 10000;	// in milliseconds

		if(!modemport.openPort()){
			logError("Error: Failed to open port "+modemport.getSystemPortName()+"\n");
			return null;
		}

		String pdu = null;
		if(number.charAt(0) == '+'){
			pdu = ModemUtil.encodePDU(number.substring(1), true, msg);
		} else {
			pdu = ModemUtil.encodePDU(number, false, msg);
		}

		configPort(modemport, baudrate);
		PrintStream out = new PrintStream(modemport.getOutputStream());

		// enable numeric error report
		out.print("AT+CMEE=1\r");
		out.flush();

		String resp = checkResponse(modemport, "OK");
		if(resp == null){
			out.close();
			modemport.closePort();
			return null;
		}

		// set text encoding to GSM
		out.print("AT+CSCS=\"GSM\"\r");
		out.flush();

		resp = checkResponse(modemport, "OK");
		if(resp == null){
			out.close();
			modemport.closePort();
			return null;
		}

		// set PDU mode
		out.print("AT+CMGF=0\r");
		out.flush();

		resp = checkResponse(modemport, "OK");
		if(resp == null){
			out.close();
			modemport.closePort();
			return null;
		}

		// set length of PDU
		out.printf("AT+CMGS=%d\r", pdu.length()/2 - 1);	// subtract 1 from length because the first byte (SMSC) is excluded from this length
		out.flush();

		resp = checkResponse(modemport, "> ");
		if(resp == null){
			out.close();
			modemport.closePort();
			return null;
		}

		// write the PDU + terminating character
		out.print(pdu);
		byte[] end = {0x1A};
		modemport.writeBytes(end, 1);

		logVerboseOutput("Sending SMS...\n");

		resp = checkResponse(modemport, "+CMGS: ", sms_timeout_ms);
		if(resp == null){
			out.close();
			modemport.closePort();
			return null;
		}

		logVerboseOutput("Done\n");

		out.close();
		modemport.closePort();
		return Integer.parseInt(resp.substring(7));
	}

	private static SerialPort[] scanForModemPorts(){

		// function scanForModemPorts
		// Scans for modems on all serial ports, prints progress messages, and returns an array of modem ports.
		// Returns an empty array if no modems were found. Returns null if no serial ports were found at all.

		SerialPort[] availablePorts = SerialPort.getCommPorts();

		if(availablePorts.length == 0){
			logVerboseOutput("No serial ports found.\n\n");
			return null;
		}

		int modem_ports_cnt = 0;
		int[] modem_ports_indexes = new int[availablePorts.length];

		for(int i = 0; i < availablePorts.length; i++){
			SerialPort port = availablePorts[i];

			logVerboseOutput("Found serial port " + port.getSystemPortName()+"\n");
			logVerboseOutput("  description: " +port.getDescriptivePortName()+"\n");
			logVerboseOutput("  opening...");

			if(!port.openPort()){
				logVerboseOutput("failed\n");
				continue;
			}
			logVerboseOutput("success\n");
			logVerboseOutput("  sending ATQ0 command...");

			configPort(port, baudrate);

			PrintStream out = new PrintStream(port.getOutputStream());
			out.print("ATQ0\r\n");
			out.close();	// also helps flush the buffer

			long cmd_sent_time = System.nanoTime();
			int timeout = 2000; // in milliseconds
			boolean received_data = false;

			// loop for detecting "OK"
			responseScanLoop:
			while(true){
				if(port.bytesAvailable() > 0){
					received_data = true;
					String[] input_lines = ModemUtil.serialReadLines(port);
					if(input_lines.length > 0){
						for(String s : input_lines){
							if(s.length() >= 2 && s.substring(0,2).equals("OK")){
								logVerboseOutput("received OK\n");
								modem_ports_indexes[modem_ports_cnt++] = i;
								break responseScanLoop;
							}
						}
					}
				}
				if((System.nanoTime()-cmd_sent_time)/1000000 > timeout){
					if(received_data){
						logVerboseOutput("timed out before receiving OK (but received other data)\n");
					}else{
						logVerboseOutput("timed out before receiving any data\n");
					}
					break;
				}
			}
			port.closePort();
		}

		SerialPort[] modemPorts = new SerialPort[modem_ports_cnt];

		if(modem_ports_cnt > 0){
			for(int i = 0; i < modem_ports_cnt; i++){
				modemPorts[i] = availablePorts[modem_ports_indexes[i]];
			}
		}

		return modemPorts;
	}
}
