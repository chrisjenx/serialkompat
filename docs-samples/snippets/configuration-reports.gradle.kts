serialkompat {
    reports {
        json { required.set(true) }               // default -> build/serialkompat/report.json
        sarif { required.set(true) }              // opt-in  -> build/serialkompat/report.sarif
    }
}
