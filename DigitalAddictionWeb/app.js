// --- 1. FIREBASE CONFIG ---
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

// --- 2. INITIALIZE FIREBASE ---
if (!firebase.apps.length) {
    firebase.initializeApp(firebaseConfig);
}
const auth = firebase.auth();
const db = firebase.database();

// --- 3. PAGE LOAD HANDLER ---
window.onload = function () {
    if (window.location.pathname.endsWith("dashboard.html")) {
        loadDashboard();
    }
};

// --- 4. AUTH FUNCTIONS ---
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

// --- 5. APP ICON HELPER ---
function getAppEmoji(packageName, appName) {
    const name = (appName || packageName).toLowerCase();
    if (name.includes("instagram"))  return "📸";
    if (name.includes("whatsapp"))   return "💬";
    if (name.includes("youtube"))    return "▶️";
    if (name.includes("facebook"))   return "👤";
    if (name.includes("twitter") || name.includes("x.com")) return "🐦";
    if (name.includes("snapchat"))   return "👻";
    if (name.includes("tiktok"))     return "🎵";
    if (name.includes("spotify"))    return "🎧";
    if (name.includes("gpay") || name.includes("pay")) return "💳";
    if (name.includes("chrome") || name.includes("browser")) return "🌐";
    if (name.includes("message") || name.includes("sms")) return "✉️";
    if (name.includes("game"))       return "🎮";
    if (name.includes("camera"))     return "📷";
    if (name.includes("maps"))       return "🗺️";
    if (name.includes("gmail") || name.includes("mail")) return "📧";
    return "📱";
}

// --- 6. FILTER STATE ---
let currentFilter = 'default';
let cachedAppData = [];   // holds rendered app objects after Promise.all
let currentUid = null;

function setFilter(filter, btn) {
    currentFilter = filter;
    // Update pill styles
    document.querySelectorAll('.filter-pill').forEach(p => p.classList.remove('active'));
    btn.classList.add('active');
    renderApps();
}

// --- 7. DASHBOARD LOGIC ---
function loadDashboard() {
    const urlParams = new URLSearchParams(window.location.search);
    currentUid = urlParams.get('uid') || localStorage.getItem("user_uid");

    if (!currentUid) {
        window.location.href = "index.html";
        return;
    }

    const uid = currentUid;
    const appListDiv = document.getElementById("app-list");

    // Local date
    const date = new Date();
    const offset = date.getTimezoneOffset();
    const today = new Date(date.getTime() - (offset * 60 * 1000)).toISOString().split('T')[0];

    console.log("Looking for data at: users/" + uid + "/usage/" + today);

    db.ref('users/' + uid + '/usage/' + today).on('value', (snapshot) => {
        const apps = snapshot.val();

        if (!apps) {
            appListDiv.innerHTML = `
                <div class="empty-state">
                    <span class="icon">📭</span>
                    No usage data for today.<br>Use your phone to generate data.
                </div>`;
            cachedAppData = [];
            return;
        }

        updateChart(apps);

        // Filter out own app first
        const appKeys = Object.keys(apps).filter(key => {
            return apps[key].packageName !== "com.example.digitaladdiction";
        });

        // Fetch ALL restriction statuses in parallel, THEN render — prevents duplicates
        const restrictionPromises = appKeys.map(key => {
            const firebaseKey = apps[key].packageName.replace(/\./g, "_");
            return db.ref('users/' + uid + '/restrictions/' + firebaseKey)
                .once('value')
                .then(snap => ({ firebaseKey, isBlocked: snap.val() === true }));
        });

        Promise.all(restrictionPromises).then(restrictionResults => {
            // Build a rich data array and cache it
            cachedAppData = appKeys.map((key, index) => {
                const appData   = apps[key];
                const appName   = appData.appName || appData.packageName;
                const firebaseKey = appData.packageName.replace(/\./g, "_");
                const minutes   = Math.round(appData.durationMs / 60000);
                const lastUsed  = appData.lastUsedTime || 0; // ms epoch
                const emoji     = getAppEmoji(appData.packageName, appName);
                const isBlocked = restrictionResults[index].isBlocked;
                return { appName, firebaseKey, minutes, lastUsed, emoji, isBlocked };
            });

            renderApps();
        });
    });
}

// --- 8. RENDER APPS (respects current filter) ---
function renderApps() {
    const appListDiv = document.getElementById("app-list");
    if (!appListDiv) return;

    let sorted = [...cachedAppData];

    if (currentFilter === 'latest') {
        // Sort by lastUsedTime descending (most recently used first)
        sorted.sort((a, b) => b.lastUsed - a.lastUsed);
    } else if (currentFilter === 'max') {
        // Sort by minutes descending (highest usage first)
        sorted.sort((a, b) => b.minutes - a.minutes);
    }
    // 'default' keeps original order from Firebase

    appListDiv.innerHTML = "";

    if (sorted.length === 0) {
        appListDiv.innerHTML = `
            <div class="empty-state">
                <span class="icon">📭</span>
                No usage data for today.<br>Use your phone to generate data.
            </div>`;
        return;
    }

    sorted.forEach((app, index) => {
        const { appName, firebaseKey, minutes, lastUsed, emoji, isBlocked } = app;

        // Sub-label shown under usage depending on filter
        let subLabel = `${minutes} min usage`;
        if (currentFilter === 'latest' && lastUsed > 0) {
            const diff = Math.round((Date.now() - lastUsed) / 60000);
            subLabel = diff < 60
                ? `Last used ${diff} min ago`
                : `Last used ${Math.round(diff/60)}h ago`;
        } else if (currentFilter === 'max') {
            subLabel = `${minutes} min — #${index + 1} most used`;
        }

        const div = document.createElement("div");
        div.className = "app-item";
        div.style.animationDelay = (index * 50) + "ms";
        div.innerHTML = `
            <div class="app-icon">${emoji}</div>
            <div class="app-info">
                <div class="app-name">${appName}</div>
                <div class="app-usage">${subLabel}</div>
            </div>
            <button id="btn-${firebaseKey}"
                    class="toggle-btn ${isBlocked ? 'btn-blocked' : 'btn-active'}"
                    onclick="toggleBlock('${currentUid}', '${firebaseKey}', ${isBlocked})">
                ${isBlocked ? 'BLOCKED' : 'ACTIVE'}
            </button>
        `;
        appListDiv.appendChild(div);
    });
}

// --- 9. BLOCKING LOGIC ---
function toggleBlock(uid, packageKey, currentStatus) {
    const newStatus = !currentStatus;
    db.ref('users/' + uid + '/restrictions/' + packageKey).set(newStatus);

    // Update cache so re-renders reflect the new state
    const entry = cachedAppData.find(a => a.firebaseKey === packageKey);
    if (entry) entry.isBlocked = newStatus;

    // Instant UI update
    const btn = document.getElementById("btn-" + packageKey);
    if (btn) {
        btn.className = "toggle-btn " + (newStatus ? "btn-blocked" : "btn-active");
        btn.textContent = newStatus ? "BLOCKED" : "ACTIVE";
        btn.onclick = () => toggleBlock(uid, packageKey, newStatus);
    }
}

// --- 8. CHART LOGIC ---
let myChart = null;

function updateChart(apps) {
    const canvas = document.getElementById('usageChart');
    if (!canvas) return;

    const ctx = canvas.getContext('2d');
    const labels = [], dataPoints = [], colors = [];

    const palette = [
        "#ff6b9d", "#5b8dee", "#ffd166", "#4cd9a0",
        "#a78bfa", "#ff9f43", "#38c9e0", "#f74444"
    ];

    let colorIndex = 0;
    Object.keys(apps).forEach(key => {
        const app = apps[key];
        const minutes = Math.round(app.durationMs / 60000);
        if (minutes > 0) {
            labels.push(app.appName || app.packageName);
            dataPoints.push(minutes);
            colors.push(palette[colorIndex % palette.length]);
            colorIndex++;
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
                borderWidth: 3,
                borderColor: '#ffffff',
                hoverOffset: 6
            }]
        },
        options: {
            responsive: true,
            cutout: '68%',
            plugins: {
                legend: {
                    position: 'bottom',
                    labels: {
                        font: { family: 'Nunito', size: 11, weight: '700' },
                        color: '#8a90b0',
                        boxWidth: 12,
                        padding: 10
                    }
                },
                title: { display: false }
            }
        }
    });
}