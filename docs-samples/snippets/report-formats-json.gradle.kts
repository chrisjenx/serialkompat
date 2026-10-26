serialkompat {
  reports {
    json {
      required.set(true)                                  // default
      outputLocation.set(layout.buildDirectory.file("serialkompat/report.json"))
    }
  }
}
