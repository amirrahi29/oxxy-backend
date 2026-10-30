# Oxxy Backend — EKS Deployment

Neeche ke steps order mein follow karo.

## 1. Prerequisites

Inhe pehle install ya ready rakho:

- AWS CLI
- kubectl
- Helm
- Terraform
- GitHub repo
- ECR repo `oxxy-backend`, region `ap-south-1`
- EKS cluster `oxxy`

## 2. Clone repo

```bash
git clone https://github.com/amirrahi29/oxxy-backend.git
cd oxxy-backend
```

## 3. Terraform

`main.tf` isi folder mein hai. Yahi se chalao:

```bash
terraform init
terraform plan
terraform apply
```

Yeh ECR repo, GitHub OIDC, VPC, aur EKS cluster `oxxy` banata hai.

## 4. kubectl

```bash
aws eks update-kubeconfig --region ap-south-1 --name oxxy
kubectl get nodes
```

## 5. GitHub Actions

File `.github/workflows/deploy.yml` hai. Har push par yeh image build karta hai, ECR par `latest` aur git SHA push karta hai, phir `blue.yaml` ke saath `helm upgrade` chalata hai. Har push blue ko auto-deploy karta hai.

## 6. Blue-green

- `blue.yaml`: tag `latest`. Yahi stable version hai.
- `green.yaml`: tag woh git SHA hai jo ECR mein hai.

Green par jao:

```bash
helm upgrade --install oxxy-backend ./oxxy-backend -n default -f oxxy-backend/green.yaml
```

Blue par wapas:

```bash
helm upgrade --install oxxy-backend ./oxxy-backend -n default -f oxxy-backend/blue.yaml
```

Switch sirf tag badalta hai. Nayi build nahi banti. Image ECR mein rehti hai.

`blue.yaml` delete mat karna. `values.yaml` ko rename karke `blue.yaml` kar diya gaya hai.

## 7. Verify

Blue par `version=latest` dikhega. Green par `version=<SHA>`.

```bash
kubectl get pods -n default --show-labels
```

Image mein colon ke baad tag hota hai.

```bash
kubectl describe pod <pod-name> -n default
```

## 8. Notes

- Cluster mein `blue` ya `green` word nahi hota. Sirf tag aur label unhe alag karte hain.
- `version` label `templates/deployment.yaml` ke `{{ .Values.image.tag }}` se aata hai.
- Labels ke neeche `app` aur `version` alag lines par hone chahiye: `app: oxxy-backend` aur `version: {{ .Values.image.tag }}`.
