This guide details the process of configuring HashiCorp Vault
to securely manage secrets for a Kubernetes application, using the Kubernetes authentication method.

### 1. Enable Kubernetes Authentication in Vault

First, we need to enable the Kubernetes authentication method in Vault.
This allows applications running in Kubernetes to authenticate with Vault using their Service Account JWT tokens.

```bash
# Enable the Kubernetes auth method
kubectl -n vault exec -it statefulset/vault -- \
  env VAULT_ADDR=http://vault.vault.svc.cluster.local:8200 \
  sh -xc 'vault auth enable kubernetes || true'
```

* `vault auth enable kubernetes`: This command enables the Kubernetes authentication backend.

### 2. Configure the Kubernetes Authentication Method

Next, we configure the Kubernetes auth method with the details of your Kubernetes cluster. This allows Vault to validate
the Service Account tokens presented by applications.

```bash
# Get the Kubernetes CA certificate
kubectl -n kube-system get configmap kube-root-ca.crt -o jsonpath='{.data.ca\.crt}' > /tmp/ca.crt

# Copy the CA certificate to the Vault pod
kubectl -n vault cp /tmp/ca.crt vault-0:/tmp/ca.crt

# Configure the Kubernetes auth method in Vault
kubectl -n vault exec -it statefulset/vault -- \
  env VAULT_ADDR=http://vault.vault.svc.cluster.local:8200 \
  sh -xc 'vault write auth/kubernetes/config \
    kubernetes_host="https://kubernetes.default.svc" \
    kubernetes_ca_cert="$(cat /tmp/ca.crt)" \
    issuer="https://kubernetes.default.svc.cluster.local" \
    disable_iss_validation="false" \
    disable_local_ca_jwt="false"'
```

* We retrieve the Kubernetes cluster's CA certificate, which is used by Vault to verify the authenticity of the
  Kubernetes API server.
* We copy this certificate into the Vault pod.
* `vault write auth/kubernetes/config`: This command configures the connection details for Vault to communicate with the
  Kubernetes API.
    * `kubernetes_host`: The address of the Kubernetes API server.
    * `kubernetes_ca_cert`: The path inside the Vault pod to the CA certificate.
    * `issuer`: The JWT issuer identifier for the Kubernetes cluster.

### 3. Create a Secrets Engine for the Application

We will enable a Key-Value (KV) version 2 secrets engine. This is where the application's secrets will be stored.
The secrets engine will be created at the path `sample-app`, which matches the configuration in
`secret-provider-class.yaml`.

```bash
# Enable the KVv2 secrets engine at the 'sample-app' path
kubectl -n vault exec -it statefulset/vault -- \
  env VAULT_ADDR=http://vault.vault.svc.cluster.local:8200 \
  sh -xc 'vault secrets enable -path=sample-app -description="secrets for sample-app" kv-v2 || true'
```

* `vault secrets enable -path=sample-app ... kv-v2`: This enables a KV version 2 secrets engine at the specified path.

### 4. Create a Vault Policy

A policy defines the permissions that an authenticated user or application has. We'll create a policy named `spc-read`
that grants read-only access to secrets stored for the `sample-app`.

```bash
# Define the policy in HCL (HashiCorp Configuration Language)
cat >/tmp/spc-read.hcl <<'EOF'
path "sample-app/data/*"     { capabilities = ["read"] }
path "sample-app/metadata/*" { capabilities = ["list","read"] }
EOF

# Create the policy in Vault
kubectl -n vault exec -it statefulset/vault -- \
  env VAULT_ADDR=http://vault.vault.svc.cluster.local:8200 \
  POLICY="$(cat /tmp/spc-read.hcl)" \
  sh -xc 'echo "$POLICY" | vault policy write spc-read -'
```

* The policy grants `read` access to the secret data under `sample-app/data/*`.
* It also grants `list` and `read` capabilities on `sample-app/metadata/*`.

### 5. Create a Vault Role

A role connects the Kubernetes authentication with Vault policies. We will create a role that binds to the `default`
service account in the `sample-app` namespace and assigns it the `spc-read` policy.

```bash
# Create the role
kubectl -n vault exec -it statefulset/vault -- \
  env VAULT_ADDR=http://vault.vault.svc.cluster.local:8200 \
  sh -xc 'vault write auth/kubernetes/role/spc-read \
    bound_service_account_names="default" \
    bound_service_account_namespaces="sample-app" \
    token_policies="spc-read" \
    audience="https://kubernetes.default.svc.cluster.local" \
    token_ttl=1h token_max_ttl=24h'
```

* `bound_service_account_names`: Specifies the name of the service account that can authenticate using this role.
* `bound_service_account_namespaces`: Specifies the namespace of the service account.
* `token_policies`: Attaches the `spc-read` policy to any token issued for this role.
* This configuration ensures that only pods running with the `default` service account in the `sample-app` namespace can
  assume this role and get the associated permissions.

### 6. Create a Secret

Finally, let's create a secret for the application to use.

```bash
# Create a test secret in the sample-app secrets engine
kubectl -n vault exec -it statefulset/vault -- \
  env VAULT_ADDR=http://vault.vault.svc.cluster.local:8200 \
  sh -xc 'vault kv put sample-app/apps/demo username=demo password=s3cr3t'
```

This command stores a secret with a `username` and `password` at the path `sample-app/apps/demo`. Your `sample-app`
application, when deployed, will be able to read this secret using the CSI driver and the `SecretProviderClass` you have
defined.

### 7. Cleanup (Optional)

If you need to tear down the configuration, you can use the following commands to undo the setup:

```bash
kubectl -n vault exec -it statefulset/vault -- \
  env VAULT_ADDR=http://vault.vault.svc.cluster.local:8200 \
  sh -xc 'vault auth disable kubernetes; vault secrets disable sample-app; vault policy delete spc-read; vault delete auth/kubernetes/role/spc-read'
```

## Verification options

- Inspect the Vault configuration:
    ```shell
    kubectl -n vault exec -it statefulset/vault -- sh -xc 'vault auth list; echo; vault read auth/kubernetes/config; echo; vault read auth/kubernetes/role/spc-read; echo; vault policy list; echo; vault policy read spc-read;'
    ```

- Create a token for the `default` service account in the `sample-app` namespace and test the authentication:
    ```shell
    kubectl -n vault exec -it statefulset/vault -- APP_JWT="$(kubectl -n sample-app create token default)" sh -xc 'vault write -format=json auth/kubernetes/login role=spc-read jwt="$APP_JWT"'
    ```

## Pitfalls

* Starting with Kubernetes 1.21, the configuration of `token_reviewer_jwt` and validation of the token issuer changed.
  See [Vault Docs > AuthN methods > K8s](https://developer.hashicorp.com/vault/docs/auth/kubernetes#kubernetes-1-21) for
  more details.
