# 🚀 6G Network Simulator (Java Edition)

![Java](https://img.shields.io/badge/Java-JDK%2017+-red?logo=java&logoColor=white)
![Version](https://img.shields.io/badge/Version-1.0.0-blue)
![License](https://img.shields.io/badge/License-MIT-green)

👨‍💻 **Developer:** Shiboshree Roy  

---

## 📖 About the Project
The **6G Network Simulator** is a **research-grade simulation platform** built using the **Java Development Kit (JDK 17+)**.

It demonstrates **core concepts of 6G wireless communication**, including:  
- 🛰 Ultra-low-latency device-to-device communication  
- 🧠 Extensible AI-driven bandwidth/resource allocation  
- 🔀 Network Slicing (IoT vs. XR/AR traffic separation)  
- 🔒 Secure communication with AES-GCM encryption  
- 📊 Real-time metrics monitoring (latency, throughput, drops)  

This project acts as a **foundation for students, researchers, and developers** to explore **6G technologies in software** before hardware deployments arrive in the next decade.  

---

## ⚡ Features
✅ **Node Simulation**: Each device is a Java thread that communicates over simulated channels  
✅ **Network Slices**: Virtual networks (`IoT`, `XR/AR`) with bandwidth and latency guarantees  
✅ **Channel Model**: Configurable latency, jitter, and packet loss  
✅ **Encryption**: End-to-end secure messages using AES/GCM  
✅ **Metrics**: Automatic measurement of throughput, latency, and packet drops  
✅ **Scalability**: Built on Executors, Concurrency, and NIO  

---

## 🛠 Tech Stack
- **Language**: Java (JDK 17+)  
- **Build Tool**: Single-file Java (can also run with Maven/Gradle)  
- **Libraries**:  
  - `javax.crypto` (encryption)  
  - Java Concurrency APIs (simulation scheduling)  
- **Extensible with**:  
  - JavaFX (live dashboard)  
  - Prometheus/Grafana (metrics visualization)  
  - DL4J/TensorFlow-Java (AI/ML integration)  

---

## 📂 Project Structure
```

SixGSimulator.java    # Single-file main source
README.md             # Documentation

````

---

## ▶️ How to Run
```bash
# Compile
javac SixGSimulator.java

# Run
java SixGSimulator
````

---

## 👨‍💻 Developer

**Name**: Shiboshree Roy
**Role**: Java/JDK Developer & Research Enthusiast
**Focus**: Building real-world, simulation-based, and AI-powered software systems

---

## 🎯 Future Enhancements

* 📡 Integration with **5G/6G testbeds** (OpenAirInterface, ns-3)
* 🎨 JavaFX/Grafana **dashboard for live metrics**
* 🧠 AI-based adaptive resource allocation (reinforcement learning)
* 🌐 **Cloud-native deployment** with Docker/Kubernetes

---

## 📜 License

This project is licensed under the **MIT License*