package com.example.untiswecker

import android.net.Uri
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.example.untiswecker.TimetableUpdateWorker

@Composable
fun LoginScreen(onLoginSuccess: (String, String, String, String, Int?, Int?) -> Unit) {
    var server by remember { mutableStateOf("tipo.webuntis.com") }
    var school by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var isScanning by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
        if (!isGranted) {
            isScanning = false
            errorMessage = "Camera permission is required to scan QR codes"
        }
    }

    if (isScanning) {
        LaunchedEffect(Unit) {
            if (!hasCameraPermission) {
                launcher.launch(Manifest.permission.CAMERA)
            }
        }

        if (hasCameraPermission) {
            Box(modifier = Modifier.fillMaxSize()) {
                QrScanner(onScan = { data ->
                    isScanning = false
                    Log.d("LoginScreen", "Scanned QR: $data")
                    try {
                        val uri = Uri.parse(data)
                        
                        // Handle untis://setschool?url=gso.webuntis.com&school=gso&user=iaf31&key=...
                        if (uri.scheme == "untis") {
                            uri.getQueryParameter("url")?.let { server = it }
                            uri.getQueryParameter("school")?.let { school = it }
                            uri.getQueryParameter("user")?.let { username = it }
                            uri.getQueryParameter("key")?.let { password = it }
                        } else {
                            // Parse WebUntis URL: https://tipo.webuntis.com/WebUntis/?school=HTL#/login?user=jsmith&key=SECRET
                            val host = uri.host
                            val schoolParam = uri.getQueryParameter("school")
                            
                            if (host != null) server = host
                            if (schoolParam != null) school = schoolParam
                            
                            val fragment = uri.fragment
                            if (fragment != null && fragment.contains("user=")) {
                                val fragmentUri = Uri.parse("http://dummy?" + fragment.substringAfter("?"))
                                username = fragmentUri.getQueryParameter("user") ?: username
                                password = fragmentUri.getQueryParameter("key") ?: password
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("LoginScreen", "Failed to parse QR", e)
                        errorMessage = "Invalid Untis QR code"
                    }
                })
            
            IconButton(
                onClick = { isScanning = false },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
            ) {
                Icon(Icons.Default.Close, contentDescription = "Close Scanner", tint = Color.White)
            }
        }
    }
}
if (isScanning) return

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Sign in with Untis",
                style = MaterialTheme.typography.headlineMedium
            )
            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = { isScanning = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Scan QR Code")
            }

            Spacer(modifier = Modifier.height(16.dp))
            
            OutlinedTextField(
                value = server,
                onValueChange = { server = it },
                label = { Text("Server") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(16.dp))
            
            OutlinedTextField(
                value = school,
                onValueChange = { school = it },
                label = { Text("School") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(16.dp))
            
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("Username") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(16.dp))
            
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            
            errorMessage?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = it, color = MaterialTheme.colorScheme.error)
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            
            Button(
                onClick = {
                    isLoading = true
                    errorMessage = null
                    scope.launch {
                        val client = UntisClient(server, school)
                        val response = client.authenticate(username, password)
                        isLoading = false
                        if (response.result != null || (response.error == null && response.jsonrpc == "2.0")) {
                            val result = response.result
                            val pId = result?.extractPersonId()
                            val pType = result?.extractPersonType() ?: 5
                            
                            Log.d("LoginScreen", "Login successful. Extracted personId=$pId, personType=$pType")
                            onLoginSuccess(server, school, username, password, pId, pType)
                            TimetableUpdateWorker.schedule(context)
                        } else {
                            errorMessage = response.error?.message ?: "Login failed"
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading
            ) {
                Text(if (isLoading) "Logging in..." else "Login")
            }
        }
    }
}
