package com.example.remotecamera;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

public class SettingsDialog extends DialogFragment {

    private EditText inputField;

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        LayoutInflater inflater = requireActivity().getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.fragment_port_settings_dialog, null);
        inputField = dialogView.findViewById(R.id.port_input);


        if (savedInstanceState != null) {
            if ((savedInstanceState.getString("port_input")) != null) {
                inputField.setText(savedInstanceState.getString("port_input"));
            }
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        builder.setTitle("Port Setting")
                .setView(dialogView)
                .setPositiveButton("Ok", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        MainActivity mainActivity = (MainActivity) requireActivity();
                        String portInput = inputField.getText().toString();
                        if(!portInput.isEmpty()) {
                            try {
                                int portInt = Integer.parseInt(portInput);
                                if (portInt >= 0 && portInt <= 65535) {
                                    mainActivity.changePort(portInt);
                                    dialog.dismiss();
                                } else {
                                    Toast.makeText(getContext(), "Range must be between 0-65535", Toast.LENGTH_SHORT).show();
                                }
                            } catch(NumberFormatException nfe) {
                                Toast.makeText(getContext(), "Invalid port", Toast.LENGTH_SHORT).show();
                            }
                        }
                    }
                })
                .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                    }
                });
        return builder.create();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (inputField != null) {
            outState.putString("port_input", inputField.getText().toString());
        }
    }

}
