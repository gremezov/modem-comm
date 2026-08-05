package io.github.gremezov.modemcomm;

import javax.swing.SwingUtilities;
import javax.swing.JOptionPane;

public class Log{

	public static void verboseOutput(boolean verbose_output, String msg, Object... extra_args){

		// function verboseOutput
		// Prints output only if verbose_output is true.

		if(verbose_output){
			System.out.printf(msg, extra_args);
		}
	}

	public static void error(boolean exit_after, boolean graphical, String err_msg, Object... extra_args){

		// function errror
		// Prints an error if in terminal and displays error popup if in GUI.
		// Can optionally exit the program right after the popup is closed in GUI.

		if(graphical){
			if(SwingUtilities.isEventDispatchThread()){
				JOptionPane.showMessageDialog(null, String.format(err_msg, extra_args), "Error", JOptionPane.ERROR_MESSAGE);
				if(exit_after) System.exit(1);
			} else {
				SwingUtilities.invokeLater(() -> {
					JOptionPane.showMessageDialog(null, String.format(err_msg, extra_args), "Error", JOptionPane.ERROR_MESSAGE);
					if(exit_after) System.exit(1);
				});
			}
		} else {
			System.err.printf(err_msg, extra_args);
		}
	}

	public static void error(boolean graphical, String err_msg, Object... extra_args){

		// function error --- (for cases when exit_after is not passed) ---
		// Wrapper around error() that makes exit_after default to false if it is not passed.

		error(false, graphical, err_msg, extra_args);
	}
}
