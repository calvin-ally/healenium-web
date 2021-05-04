# healenium-web
Self-healing library for Selenium Web-based tests

[![Maven Central](https://img.shields.io/maven-central/v/com.epam.healenium/healenium-web.svg?label=Maven%20Central)](https://search.maven.org/search?q=g:%22com.epam.healenium%22%20AND%20a:%22healenium-web%22)
[![Coverage Status](https://coveralls.io/repos/github/healenium/healenium-web/badge.svg)](https://coveralls.io/github/healenium/healenium-web)
[![Build Status](https://github.com/healenium/healenium-web/workflows/Java-CI-test/badge.svg)](https://github.com/healenium/healenium-web/workflows/Java-CI-test/badge.svg)

## How to start

### 0. Start hlm-backend by [instruction](https://github.com/healenium/healenium-backend)

### 0.1 Add dependency

for Gradle projects:
``` 
dependencies {
    compile group: 'com.epam.healenium', name: 'healenium-web', version: '3.0.3-beta-8'
}
```

for Maven projects:
``` 
<dependency>
	<groupId>com.epam.healenium</groupId>
	<artifactId>healenium-web</artifactId>
	<version>3.0.3-beta-8</version>
</dependency>
```
### 1. Init driver instance of SelfHealingDriver
``` 
//declare delegate
WebDriver delegate = new ChromeDriver();
//create Self-healing driver
SelfHealingDriver driver = SelfHealingDriver.create(delegate);
 ```
### 2. Specify custom healing config file healenium.properties under test/resources directory, ex.:
``` 
recovery-tries = 1
score-cap = 0.5
heal-enabled = true
serverHost = localhost
serverPort = 7878
 ```
> recovery-tries - list of proposed healed locators

> heal-enabled - flag to enable or disable healing.
Also you can set this value via -D or System properties, for example to turn off healing for current test run: -Dheal-enabled=false

> score-cap - score value to enable healing with predefined probability of match (0.5 means that healing will be performed for new healed locators where probability of match with target one is >=50% )

> serverHost - ip or name where hlm-backend instance is installed

> serverPort - port on which hlm-backend instance is installed (7878 by default)

### 3. Simply use standard By/@FindBy to locate your elements
```
@FindBy(xpath = "//button[@type='submit']")
private WebElement testButton;
...
public void clickTestButton() {
     driver.findElement(By.cssSelector(".test-button")).click();
}
```
### 4. To disable healing for some element you can use @DisableHealing annotation over the method where element is called
```
@DisableHealing
public void clickTestButton() {
     testButton.click();
}
```
### 5. Add [hlm-report-gradle](https://github.com/healenium/healenium-report-gradle) or [hlm-report-mvn](https://github.com/healenium/healenium-report-mvn) plugin to enable reporting
### 6. Add [hlm-idea](https://github.com/healenium/healenium-idea) plugin to enable locator updates in your TAF code
### 7. Run tests as usual using Maven mvn clean test or Gradle ./gradlew clean test