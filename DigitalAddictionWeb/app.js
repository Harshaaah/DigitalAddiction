// --- 1. PASTE YOUR FIREBASE CONFIG HERE ---
const firebaseConfig = {
  apiKey: "AIzaSyBKWsjaUOtk8M9CuDuhMtIMXG7ASQMlAIs",
  authDomain: "digitaladdictiontracker.firebaseapp.com",
  databaseURL: "https://digitaladdictiontracker-default-rtdb.firebaseio.com",
  projectId: "digitaladdictiontracker",
  storageBucket: "digitaladdictiontracker.firebasestorage.app",
  messagingSenderId: "380081655454",
  appId: "1:380081655454:web:bbcdda79563fd3e9c5358f",
  measurementId: "G-WERWE4VBYL"
};

// // Initialize Firebase
// firebase.initializeApp(firebaseConfig);
// const auth = firebase.auth();
// const db = firebase.database();

// // --- 2. LOGIN LOGIC ---
// function login() {
//     const email = document.getElementById("email").value;
//     const pass = document.getElementById("password").value;
//     const errorMsg = document.getElementById("error-msg");

//     auth.signInWithEmailAndPassword(email, pass)
//         .then((userCredential) => {
//             // Success! Save UID and go to Dashboard
//             localStorage.setItem("user_uid", userCredential.user.uid);
//             window.location.href = "dashboard.html";
//         })
//         .catch((error) => {
//             errorMsg.innerText = error.message;
//         });
// }

// // Check if we are on dashboard page to load data
// if (window.location.pathname.endsWith("dashboard.html")) {
//     loadDashboard();
// }
// Initialize Firebase
if (!firebase.apps.length) {
    firebase.initializeApp(firebaseConfig);
}
const auth = firebase.auth();
const db = firebase.database();

// --- 2. PAGE LOAD HANDLER (Fixes ReferenceError) ---
window.onload = function() {
    // Check if we are on the dashboard page
    if (window.location.pathname.endsWith("dashboard.html")) {
        loadDashboard();
    }
};

// --- 3. AUTHENTICATION FUNCTIONS ---
function login() {
    const email = document.getElementById("email").value;
    const pass = document.getElementById("password").value;
    const errorMsg = document.getElementById("error-msg");

    auth.signInWithEmailAndPassword(email, pass)
        .then((userCredential) => {
            localStorage.setItem("user_uid", userCredential.user.uid);
            window.location.href = "dashboard.html";
        })
        .catch((error) => {
            errorMsg.innerText = error.message;
        });
}

function logout() {
    auth.signOut().then(() => {
        localStorage.removeItem("user_uid");
        window.location.href = "index.html";
    });
}

// --- 4. DASHBOARD CORE LOGIC ---
// function loadDashboard() {
//     const uid = localStorage.getItem("user_uid");
//     if (!uid) {
//         window.location.href = "index.html"; // Kick out if not logged in
//         return;
//     }
function loadDashboard() {
    // --- FIX: Check URL for UID first (Auto-Login from App) ---
    const urlParams = new URLSearchParams(window.location.search);
    let uid = urlParams.get('uid');

    // If no UID in URL, check LocalStorage (Standard Web Login)
    if (!uid) {
        uid = localStorage.getItem("user_uid");
    }
    // -----------------------------------------------------------

    if (!uid) {
        window.location.href = "index.html"; // Kick out if totally unknown
        return;
    }

    const appListDiv = document.getElementById("app-list");
    
    

   
    
    // --- DATE FIX: Get Local Date instead of UTC ---
    const date = new Date();
    const offset = date.getTimezoneOffset();
    const today = new Date(date.getTime() - (offset*60*1000)).toISOString().split('T')[0];
    
    console.log("Looking for data at: users/" + uid + "/usage/" + today);

    // Listen to Database Changes
    db.ref('users/' + uid + '/usage/' + today).on('value', (snapshot) => {
        appListDiv.innerHTML = ""; // Clear list
        const apps = snapshot.val();

        if (!apps) {
            appListDiv.innerHTML = "<p>No usage data found for date: " + today + "</p><p>Use your phone for a minute to generate data.</p>";
            return;
        }

        // Draw the Chart
        updateChart(apps);

        // Generate the List
        Object.keys(apps).forEach(key => {
            const appData = apps[key];
            const packageName = appData.packageName;
             // --- FIX: HIDE YOUR OWN APP ---
            // Replace this string with your exact package name from the screenshot
            if (packageName === "com.example.digitaladdiction") {
                return; // Skip this loop iteration
            } 
            const appName = appData.appName || packageName;
            // Create a safe key for Firebase (replace dots with underscores)
            const firebaseKey = packageName.replace(/\./g, "_"); 

            const div = document.createElement("div");
            div.className = "app-item";
            
            // Check restriction status
            db.ref('users/' + uid + '/restrictions/' + firebaseKey).once('value', (resSnap) => {
                const isBlocked = resSnap.val() === true;
                
                div.innerHTML = `
                    <div style="text-align:left;">
                        <strong>${appName}</strong><br>
                        <small style="color:#666">${packageName}</small><br>
                        <small>Usage: ${Math.round(appData.durationMs/60000)} mins</small>
                    </div>
                    <button id="btn-${firebaseKey}" 
                            class="toggle-btn ${isBlocked ? 'btn-block' : 'btn-allow'}"
                            onclick="toggleBlock('${uid}', '${firebaseKey}', ${isBlocked})">
                        ${isBlocked ? 'UNBLOCK' : 'BLOCK'}
                    </button>
                `;
                appListDiv.appendChild(div);
            });
        });
    });
}

// --- 5. BLOCKING LOGIC ---
function toggleBlock(uid, packageKey, currentStatus) {
    const newStatus = !currentStatus;
    // Write to Firebase
    db.ref('users/' + uid + '/restrictions/' + packageKey).set(newStatus);
    console.log("Set " + packageKey + " to " + newStatus);
    // The UI updates automatically via the listener above
}

// --- 6. CHART LOGIC ---
let myChart = null;

function updateChart(apps) {
    // Only run if the canvas element exists
    const canvas = document.getElementById('usageChart');
    if (!canvas) return;

    const ctx = canvas.getContext('2d');
    const labels = [];
    const dataPoints = [];
    const colors = [];

    Object.keys(apps).forEach(key => {
        const app = apps[key];
        const minutes = Math.round(app.durationMs / 60000); 
        
        if (minutes > 0) { // Show all apps with > 0 usage
            labels.push(app.appName || app.packageName);
            dataPoints.push(minutes);
            colors.push(`hsl(${Math.random() * 360}, 70%, 60%)`);
        }
    });

    if (myChart) myChart.destroy();

    myChart = new Chart(ctx, {
        type: 'doughnut', 
        data: {
            labels: labels,
            datasets: [{
                data: dataPoints,
                backgroundColor: colors,
                borderWidth: 1
            }]
        },
        options: {
            responsive: true,
            plugins: {
                legend: { position: 'bottom' },
                title: { display: true, text: "Today's Usage (Minutes)" }
            }
        }
    });
}