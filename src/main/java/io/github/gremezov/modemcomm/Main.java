package io.github.gremezov.modemcomm;

import com.fazecast.jSerialComm.SerialPort;
import com.fazecast.jSerialComm.SerialPortDataListener;
import com.fazecast.jSerialComm.SerialPortEvent;
import org.apache.commons.cli.*;
import javax.swing.*;
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
import java.util.concurrent.ExecutionException;
import java.util.Scanner;
import java.util.List;
import java.util.Arrays;
import java.util.HashMap;
import java.util.stream.IntStream;

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
				Log.error(is_gui, "Error: No arguments given. Try -h for help.\n");
				System.exit(1);
			}

			if(cmdline.hasOption("help")){
				HelpFormatter formatter = new HelpFormatter();
				formatter.printHelp("modemcomm", options);
				System.exit(0);
			}
			if(cmdline.hasOption("verbose") && !is_gui){
				verbose_output = true;
			}
			if(cmdline.hasOption("end-chars")){
				terminal_end_chars = cmdline.getOptionValue("end-chars").replace("\\n", "\n").replace("\\r", "\r");
			}
			if(cmdline.hasOption("baud")){
				try{
					baudrate = Integer.parseInt(cmdline.getOptionValue("baud"));
				} catch (NumberFormatException e){
					Log.error(is_gui, "Error: Baud rate must be an integer.\n");
					if(!is_gui) System.exit(1);
				}
			}

			if(cmdline.hasOption("auto-select-port")){
				if(is_gui){
					SwingUtilities.invokeLater(() -> {
						JOptionPane.showMessageDialog(null, "Scanning serial ports for a modem...", "scan running", JOptionPane.PLAIN_MESSAGE);
					});
				} else{
					Log.verboseOutput(verbose_output, "Scanning serial ports for a modem...\n");
				}

				// use scanForModemPorts instead of startScanForModemPorts because the GUI has not been initialized yet
				SerialPort[] modemPorts = ModemUtilities.scanForModemPorts(verbose_output, baudrate);

				if(modemPorts == null){
					Log.error(is_gui, "Error: No serial ports found.\n");
					if(!is_gui) System.exit(1);
				} else if(modemPorts.length == 0){
					Log.error(is_gui, "Error: No modems found.\n");
					if(!is_gui) System.exit(1);
				} else {
					modemport = modemPorts[0];
					if(is_gui){
						SwingUtilities.invokeLater(() -> {
							JOptionPane.showMessageDialog(null, "Selecting port "+modemport.getSystemPortName(), "modem selected", JOptionPane.PLAIN_MESSAGE);
						});
					} else{
						Log.verboseOutput(verbose_output, "Selecting port "+modemport.getSystemPortName()+"\n");
					}
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
					Log.error(is_gui, "Error: Port "+manual_port_name+" does not exist.\n");
					if(!is_gui) System.exit(1);
				}
			}

			if(cmdline.hasOption("terminal")){
				is_standalone_gui_terminal = true;
				if(launchSerialTerminal(modemport, baudrate, terminal_end_chars) == -1){
					if(!is_gui) System.exit(1);	// if(!is_gui) checks necessary?
				} else{
					if(!is_gui) System.exit(0);
				}
			}
			if(cmdline.hasOption("ussd") && !is_gui){
				String response = startSendReceiveUSSD(modemport, cmdline.getOptionValue("ussd"));
				if(response == null) System.exit(1);
				System.out.println(response);
				System.exit(0);
			}
			if(cmdline.hasOption("sms") && !is_gui){
				String[] opts = cmdline.getOptionValues("sms");
				if(opts.length < 2){
					Log.error(is_gui, "Error: SMS message not given.\n");
					System.exit(1);
				}
				Integer response = startSendSMS(modemport, opts[0], opts[1]);
				if(response == null) System.exit(1);
				System.exit(0);
			}
			if(cmdline.hasOption("scan") && !is_gui){
				SerialPort[] modemPorts = startScanForModemPorts();
				if(modemPorts.length > 0){
					Log.verboseOutput(verbose_output, "Found useable modems on the following ports:\n");
					for(SerialPort sp : modemPorts){
						System.out.println(sp.getSystemPortName());
					}
				} else{
					Log.verboseOutput(verbose_output, "No useable modems found.\n");
				}
				System.exit(0);
			}
		} catch (ParseException e){
			Log.error(is_gui, "Error: Failed to parse command-line arguments. Reason: "+e.getMessage()+"\n");
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
	private static JPanel homePanelBox;
	private static JLabel homePortLabel;
	private static JComboBox<String> homePortSelectorComboBox;
	private static JButton homeModemScanButton;
	private static JLabel homeModemInfoLabel1;
	private static JLabel homeModemInfoLabel2;

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

	private static String no_port_selected_str = "- none selected -";

	private static void runGUI(){

		// function runGUI
		// Initializes and runs the swing GUI.

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

		homePanel = new JPanel(new BorderLayout());
		homePanel.setBackground(Color.LIGHT_GRAY);

		homePanelBox = new JPanel();
		homePanelBox.setLayout(new BoxLayout(homePanelBox, BoxLayout.Y_AXIS));
		homePanelBox.setBackground(homePanel.getBackground());

		homePortLabel = new JLabel("Modem Port:");
		homePortLabel.setFont(new Font("Arial", Font.BOLD, 14));

		homePortSelectorComboBox = new JComboBox<>(port_names);

		homeModemScanButton = new JButton("Detect Modems");
		homeModemScanButton.setFont(new Font("Arial", Font.BOLD, 14));

		JLabel homeModemInfoNameLabel1 = new JLabel("Manufacturer: ");
		homeModemInfoNameLabel1.setFont(new Font("Arial", Font.BOLD, 14));
		homeModemInfoLabel1 = new JLabel();
		homeModemInfoLabel1.setFont(new Font("Arial", Font.PLAIN, 14));

		JLabel homeModemInfoNameLabel2 = new JLabel("Model: ");
		homeModemInfoNameLabel2.setFont(new Font("Arial", Font.BOLD, 14));
		homeModemInfoLabel2 = new JLabel();
		homeModemInfoLabel2.setFont(new Font("Arial", Font.PLAIN, 14));

		// set the default selected item to the currently used modem port and fill modem info
		if(modemport != null){
			homePortSelectorComboBox.setSelectedItem(modemport.getSystemPortName());

			homeModemInfoLabel1.setText("---");
			homeModemInfoLabel2.setText("---");
			startGetInfo(modemport);
		}

		homePortSelectorComboBox.addActionListener(e -> {
			String selected_port_name = (String)homePortSelectorComboBox.getSelectedItem();
			if(selected_port_name.equals(no_port_selected_str)){
				modemport = null;

				homeModemInfoLabel1.setText("");
				homeModemInfoLabel2.setText("");
			} else {
				modemport = SerialPort.getCommPort(selected_port_name);

				homeModemInfoLabel1.setText("---");
				homeModemInfoLabel2.setText("---");
				startGetInfo(modemport);
			}
		});

		homeModemScanButton.addActionListener(e -> {
			startScanForModemPorts();
		});

		JPanel hp1 = new JPanel(new FlowLayout(FlowLayout.LEFT));
		hp1.add(homePortLabel);
		hp1.add(homePortSelectorComboBox);
		hp1.setBackground(homePanel.getBackground());

		JPanel hp2 = new JPanel(new FlowLayout(FlowLayout.LEFT));
		hp2.add(homeModemInfoNameLabel1);
		hp2.add(homeModemInfoLabel1);
		hp2.setBackground(homePanel.getBackground());

		JPanel hp3 = new JPanel(new FlowLayout(FlowLayout.LEFT));
		hp3.add(homeModemInfoNameLabel2);
		hp3.add(homeModemInfoLabel2);
		hp3.setBackground(homePanel.getBackground());

		JPanel hp4 = new JPanel(new FlowLayout(FlowLayout.CENTER));
		hp4.add(homeModemScanButton);
		hp4.setBackground(homePanel.getBackground());

		homePanelBox.add(Box.createVerticalStrut(30));
		homePanelBox.add(hp1);
		homePanelBox.add(hp2);
		homePanelBox.add(hp3);
		homePanelBox.add(Box.createVerticalStrut(30));
		homePanelBox.add(hp4);

		homePanel.add(homePanelBox, BorderLayout.PAGE_START);

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
				Log.error(is_gui, "Error: Please input the USSD code.");
			} else {
				startSendReceiveUSSD(modemport, input);
			}
		});

		JPanel up1 = new JPanel(new FlowLayout(FlowLayout.LEFT));
		up1.add(USSDLabel);
		up1.setBackground(USSDPanel.getBackground());

		JPanel up2 = new JPanel(new FlowLayout(FlowLayout.LEFT));
		up2.add(USSDInputField);
		up2.add(USSDSendButton);
		up2.setBackground(USSDPanel.getBackground());

		JPanel up3 = new JPanel(new FlowLayout(FlowLayout.LEFT));
		up3.add(USSDReplyAreaScrollPane);
		up3.setBackground(USSDPanel.getBackground());

		USSDPanelBox.add(up1);
		USSDPanelBox.add(up2);
		USSDPanelBox.add(Box.createVerticalStrut(30));
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
				Log.error(is_gui, "Error: Please input a recipient phone number.");
			} else if(message.equals("")){
				Log.error(is_gui, "Error: Please input a message.");
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
				Log.error(is_gui, "Error: Please select a serial port.");
				return;
			}
			String st_baudrate_str = terminalBaudInputField.getText();
			if(st_baudrate_str.equals("")){
				Log.error(is_gui, "Error: Please input the baud rate.");
				return;
			}
			int st_baudrate = 0;	// baud rate to use for the serial terminal, could be different from the global baud rate
			try{
				st_baudrate = Integer.parseInt(st_baudrate_str);
			} catch (NumberFormatException exc){
				Log.error(is_gui, "Error: Baud rate must be an integer.");
				return;
			}
			launchSerialTerminal(SerialPort.getCommPort(selected_port_name), st_baudrate, terminalEndcharsInputField.getText().replace("\\r", "\r").replace("\\n", "\n"));
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
		terminalPanelBox.add(Box.createVerticalStrut(30));
		terminalPanelBox.add(tp7);

		terminalPanel.add(terminalPanelBox, BorderLayout.PAGE_START);

		/* menu */

		menuPanel = new JPanel();
		menuPanel.setLayout(new FlowLayout(FlowLayout.LEFT, 7, 5));

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
		});

		terminalMenuButton = new JButton("Terminal");
		terminalMenuButton.setFont(new Font("Arial", Font.BOLD, 14));

		terminalMenuButton.addActionListener(e -> {
			CardLayout cl = (CardLayout)mainPanel.getLayout();
			cl.show(mainPanel, "terminalPanel");
		});

		Dimension maxMenuButtonSize = new Dimension(0, 0);

		// get max width of all button widths
		maxMenuButtonSize.width = IntStream.of(
			homeMenuButton.getPreferredSize().width,
			USSDMenuButton.getPreferredSize().width,
			SMSMenuButton.getPreferredSize().width,
			terminalMenuButton.getPreferredSize().width
		).max().getAsInt();

		// get max height of all button heights
		maxMenuButtonSize.height = IntStream.of(
			homeMenuButton.getPreferredSize().height,
			USSDMenuButton.getPreferredSize().height,
			SMSMenuButton.getPreferredSize().height,
			terminalMenuButton.getPreferredSize().height
		).max().getAsInt();

		// set all button heights and widths to be that of the largest occuring button dimensions
		homeMenuButton.setPreferredSize(maxMenuButtonSize);
		USSDMenuButton.setPreferredSize(maxMenuButtonSize);
		SMSMenuButton.setPreferredSize(maxMenuButtonSize);
		terminalMenuButton.setPreferredSize(maxMenuButtonSize);

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
			Log.error(is_gui, "Error: No port selected.\n");
			return null;
		}

		if(!modemport.openPort()){
			Log.error(is_gui, "Error: Failed to open port "+modemport.getSystemPortName()+"\n");
			return null;
		}

		Serial.config(modemport, baudrate);
		Modem modem = new Modem(modemport);

		if(is_gui){
			SwingWorker sw1 = new SwingWorker<Modem.Response<String>, String>(){
				@Override
				protected Modem.Response<String> doInBackground(){
					publish("Processing...");
					return modem.sendReceiveUSSD(ussd_code);
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
						Modem.Response<String> resp = get();
						if(resp.status() < 0){
							Log.error(is_gui, "Error: "+resp.errorMessage());
							USSDReplyArea.setText("");
						} else{
							USSDReplyArea.setText(resp.response());
						}
						USSDSendButton.setText("Send");
						USSDPanel.revalidate();
						USSDPanel.repaint();
					} catch (InterruptedException e){e.printStackTrace();} catch (ExecutionException e){e.printStackTrace();}
					modemport.closePort();
				}
			};
			sw1.execute();
			return null;
		} else {
			Log.verboseOutput(verbose_output, "USSD Processing...\n");
			Modem.Response<String> resp = modem.sendReceiveUSSD(ussd_code);
			if(resp.status() < 0){
				Log.error(is_gui, "Error: "+resp.errorMessage()+"\n");
				modemport.closePort();
				return null;
			}
			Log.verboseOutput(verbose_output, "Done\n");
			if(resp.status() == Modem.USER_REPLY_REQUIRED){
				System.out.println(resp.response());

				Scanner scanner = new Scanner(System.in);

				// print USSD reply to screen and then read and send user reply until user reply is no longer needed
				while(resp.status() == Modem.USER_REPLY_REQUIRED){
					System.out.print("> ");
					Log.verboseOutput(verbose_output, "USSD Processing...\n");
					// XXX --- TODO: edit sendReceiveUSSD to make initialization command sending optional (for cases such as these)
					resp = modem.sendReceiveUSSD(scanner.nextLine());
					if(resp.status() < 0){
						Log.error(is_gui, "Error: "+resp.errorMessage()+"\n");
						scanner.close();
						modemport.closePort();
						return null;
					}
					Log.verboseOutput(verbose_output, "Done.\n");
					System.out.println(resp.response());
				}
				scanner.close();
			}
			modemport.closePort();
			return resp.response();
		}
	}

	private static Integer startSendSMS(SerialPort modemport, String number, String msg){

		// function startSendSMS
		// GUI mode: Uses SwingWorker to run sendSMS in the background and update the UI once the message has been sent. Returns null in any case.
		// Terminal mode: Runs sendSMS and returns the message reference number. Returns null if encounters an error.

		if(msg.length() > 160){
			Log.error(is_gui, "Error: SMS message too long (over 160 characters)\n");
			return null;
		}

		String number_nospaces = number.replace(" ", "");

		if(!number_nospaces.matches("^\\+?[0-9]+$")){
			Log.error(is_gui, "Error: Invalid phone number format.\n");
			return null;
		}

		if(modemport == null){
			Log.error(is_gui, "Error: No port selected.\n");
			return null;
		}

		if(!modemport.openPort()){
			Log.error(is_gui, "Error: Failed to open port "+modemport.getSystemPortName()+"\n");
			return null;
		}

		Serial.config(modemport, baudrate);
		Modem modem = new Modem(modemport);

		if(is_gui){
			SwingWorker sw1 = new SwingWorker<Modem.Response<Integer>, String>(){
				@Override
				protected Modem.Response<Integer> doInBackground(){
					publish("Sending...");
					return modem.sendSMS(number_nospaces, msg);
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
						Modem.Response<Integer> resp = get();
						if(resp.status() < 0){
							Log.error(is_gui, "Error: "+resp.errorMessage());
						}
						SMSSendButton.setText("Send");
						SMSPanel.revalidate();
						SMSPanel.repaint();
					} catch (InterruptedException e){e.printStackTrace();} catch (ExecutionException e){e.printStackTrace();}
					modemport.closePort();
				}
			};
			sw1.execute();
			return null;
		} else {
			Log.verboseOutput(verbose_output, "Sending SMS...\n");
			Modem.Response<Integer> resp = modem.sendSMS(number_nospaces, msg);
			if(resp.status() < 0){
				Log.error(is_gui, "Error: "+resp.errorMessage()+"\n");
				modemport.closePort();
				return null;
			}
			Log.verboseOutput(verbose_output, "Done.\n");
			modemport.closePort();
			return resp.response();
		}
	}

	private static SerialPort[] startScanForModemPorts(){

		// function startScanForModemPorts
		// Terminal mode: runs scanForModemPorts and returns it's return value (array of available modem ports).
		// GUI mode: uses SwingWorker to run scanForModemPorts and update UI. Returns null.

		if(is_gui){
			SwingWorker sw1 = new SwingWorker<SerialPort[], String>(){
				@Override
				protected SerialPort[] doInBackground(){
					publish("Scanning...");
					return ModemUtilities.scanForModemPorts(verbose_output, baudrate);
				}

				@Override
				protected void process(List<String> chunks){
					homeModemScanButton.setText(chunks.get(chunks.size()-1));
					homePanel.revalidate();
					homePanel.repaint();
				}

				@Override
				protected void done(){
					try{
						SerialPort[] modem_ports = get();

						if(modem_ports != null && modem_ports.length > 0){

							// fill array with port names for the selector
							String[] modem_port_names = new String[modem_ports.length+1];
							modem_port_names[0] = no_port_selected_str;	// default option is none
							for(int i = 0; i < modem_ports.length; i++){
								modem_port_names[i+1] = modem_ports[i].getSystemPortName();
							}

							// replace old combo box model with new one
							DefaultComboBoxModel<String> newComboBoxModel = new DefaultComboBoxModel<>(modem_port_names);
							homePortSelectorComboBox.setModel(newComboBoxModel);

							// If a serial port was previously selected and if it still exists in the current modem port list then make it
							// the currently selected item in the combo box. If it doesn't exist in the new list, then deselect it.
							if(modemport != null){
								String existing_port_name = modemport.getSystemPortName();
								if(Arrays.asList(modem_port_names).contains(existing_port_name)){
									homePortSelectorComboBox.setSelectedItem(existing_port_name);
								} else {
									modemport = null;	// if the previously selected port does not exit in the current modem port list then de-select it
									homeModemInfoLabel1.setText("");
									homeModemInfoLabel2.setText("");
								}
							}

							// form a string with all the port names and display to user
							String names = String.join(", ", Arrays.copyOfRange(modem_port_names, 1, modem_port_names.length));
							JOptionPane.showMessageDialog(null, "Found modems on the following ports:\n"+names, "modems found", JOptionPane.INFORMATION_MESSAGE);
						} else {
							Log.error(is_gui, "Error: No modems found.");
						}

						homeModemScanButton.setText("Detect Modems");
						homePanel.revalidate();
						homePanel.repaint();
					} catch (InterruptedException e){e.printStackTrace();} catch (ExecutionException e){e.printStackTrace();}
				}
			};
			sw1.execute();
			return null;
		} else {
			return ModemUtilities.scanForModemPorts(verbose_output, baudrate);
		}
	}

	private static void startGetInfo(SerialPort modemport){

		// function startGetInfo
		// Uses SwingWorker to get modem info with modem.getInfo() and updates the info display on the home screen.

		if(!modemport.openPort()){
			return;
		}

		Serial.config(modemport, baudrate);
		Modem modem = new Modem(modemport);

		SwingWorker sw1 = new SwingWorker<HashMap<String, String>, Void>(){
			@Override
			protected HashMap<String, String> doInBackground(){
				return modem.getInfo();
			}

			@Override
			protected void done(){
				try{
					HashMap<String, String> info = get();
					if(info.get("Manufacturer") != null) homeModemInfoLabel1.setText(info.get("Manufacturer"));
					if(info.get("Model") != null) homeModemInfoLabel2.setText(info.get("Model"));
					homePanel.revalidate();
					homePanel.repaint();
				} catch (InterruptedException e){e.printStackTrace();} catch (ExecutionException e){e.printStackTrace();}
				modemport.closePort();
			}
		};
		sw1.execute();
	}

	private static int launchSerialTerminal(SerialPort port, int baudrate, String end_chars){

		// function launchSerialTerminal
		// Launches a serial terminal, either in the system's terminal or using a GUI.
		// GUI mode: returns -1 if error and 0 if everything ok.
		// Terminal mode: returns -1 if error and never returns (runs in a loop) if everything ok.

		if(port == null){
			Log.error(is_standalone_gui_terminal, is_gui, "Error: No port selected.\n");
			return -1;
		}

		if(!port.openPort()){
			Log.error(is_standalone_gui_terminal, is_gui, "Error: Failed to open port "+port.getSystemPortName()+"\n");
			return -1;
		}

		Serial.config(port, baudrate);
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
					System.out.print(Serial.read(port));
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

		JTextArea stArea = new JTextArea();
		stArea.setFont(new Font("Monospaced", Font.PLAIN, 14));
		stArea.setEditable(false);
		stArea.setLineWrap(true);
		stArea.setWrapStyleWord(true);

		// make text area auto-scoll down when new data appears
		DefaultCaret stAreaCaret = (DefaultCaret) stArea.getCaret();
		stAreaCaret.setUpdatePolicy(DefaultCaret.ALWAYS_UPDATE);

		/*JButton stClosePortButton = new JButton("Close port");
		stClosePortButton.setFont(new Font("Arial", Font.BOLD, 14));*/

		JScrollPane stAreaScrollPane = new JScrollPane(stArea);

		JTextField stInputField = new JTextField(40);
		stInputField.setFont(new Font("Monospaced", Font.PLAIN, 14));

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

				/*while(!isCancelled()){
					if(port.bytesAvailable() > 0){
						stArea.append(Serial.read(port));
					}
				}*/
				port.addDataListener(new SerialPortDataListener(){
					@Override
					public int getListeningEvents(){
						return SerialPort.LISTENING_EVENT_DATA_AVAILABLE;
					}
					@Override
					public void serialEvent(SerialPortEvent event){
						if(event.getEventType() == SerialPort.LISTENING_EVENT_DATA_AVAILABLE){
							stArea.append(Serial.read(port));
						}
					}
				});

				while(!isCancelled()){
					try{Thread.sleep(1000);}catch(InterruptedException exc){}
				}

				port.removeDataListener();

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
}
