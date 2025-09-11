# HMCTS Service Spring Boot Template

This repository provides a template for building Spring Boot applications. While the initial use case was for the HMCTS API Marketplace, the template is designed to be reusable across jurisdictions and is intended as a base paved path for wider adoption.

It includes essential configurations, dependencies, and recommended practices to help teams get started quickly.

**Note:** This template is not a framework, nor is it intended to evolve into one. It simply leverages the Spring ecosystem and proven libraries from the wider engineering community.

As HMCTS services are hosted on Azure, the included dependencies reflect this. Our aim is to stay as close to the cloud as possible in order to maximise alignment with the Shared Responsibility Model and achieve optimal security and operability.

## Want to Build Your Own Path?

That’s absolutely fine — but if you do, make sure your approach meets the following baseline requirements:

* Security – All services must meet HMCTS security standards, including vulnerability scanning and least privilege access.
* Observability – Logs, metrics, and traces must be integrated into HMCTS observability stack (e.g. Azure Monitoring).
* Audit – Systems must produce audit trails that meet legal and operational requirements.
* CI/CD Integration – Pipelines must include automated testing, deployments to multiple environments, and use approved tooling (e.g. GitHub Actions or Azure DevOps).
* Compliance & Policy Alignment – Services must align with HMCTS/MoJ policies (e.g. Coding in the Open, mandatory security practices).
* Ownership & Support – Domain teams must clearly own the service, maintain a support model, and define escalation paths.

## Installation

To get started with this project, you'll need Java and Gradle installed.

### Prerequisites

- ☕️ **Java 21 or later**: Ensure Java is installed and available on your `PATH`.
- ⚙️ **Gradle**: You can install Gradle using your preferred method:

  **macOS (Recommended with Homebrew):**
  ```bash
  brew install gradle
  ```

  **Other Platforms:**
  Visit the [Gradle installation guide](https://gradle.org/install/) for platform-specific instructions.

You can verify installation with:
```bash
java -version
gradle -v
```

#### Add Gradle Wrapper

run `gradle wrapper`

### Environment Setup for Local Builds

Recommended Approach for macOS Users (using `direnv`)

If you're on macOS, you can use [direnv](https://direnv.net/) to automatically load these environment variables per project:

1. Install `direnv`:
   ```bash
   brew install direnv
   ```

2. Hook it into your shell (example for bash or zsh):
   ```bash
   echo 'eval "$(direnv hook bash)"' >> ~/.bash_profile
   # or for zsh
   echo 'eval "$(direnv hook zsh)"' >> ~/.zshrc
   ```

4. Allow `direnv` to load:
   ```bash
   direnv allow
   ```

This will ensure your environment is correctly set up every time you enter the project directory.

## Static code analysis

Install PMD

```bash
brew install pmd
```
```bash
pmd check \
    --dir src/main/java \
    --rulesets \
    .github/pmd-ruleset.xml \
    --format html \
    -r build/reports/pmd/pmd-report.html
```

## Pact Provider Test

Run pact provider test and publish verification report to pact broker locally

Update .env file with below details (replacing placeholders with actual values):
```bash
export PACT_PROVIDER_VERSION="0.1.0-local-YOUR-INITIALS" # or any version you want to use
export PACT_VERIFIER_PUBLISH_RESULTS=true
export PACT_PROVIDER_BRANCH="ANY_BRANCH_NAME_THAT_IS_NOT_A_DEFAULT_ONE"
export PACT_BROKER_TOKEN="YOUR_PACTFLOW_BROKER_TOKEN"
export PACT_BROKER_URL="https://hmcts-dts.pactflow.io"
export PACT_ENV="local" # or value based on the environment you are testing against
```
Run Pact tests:
```bash
gradle pactVerificationTest
```

### Contribute to This Repository

Contributions are welcome! Please see the [CONTRIBUTING.md](.github/CONTRIBUTING.md) file for guidelines.

See also: [JWTFilter documentation](docs/JWTFilter.md)

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details
