# Oxxy Backend

Spring Boot app that returns a dummy product list from `GET /products` on port **8081**. The image is built on GitHub Actions, stored in Amazon ECR, and runs on Amazon EKS. An Application Load Balancer exposes it on the public internet.

| Item | Value |
| --- | --- |
| Region | `ap-south-1` |
| Account | `033193124350` |
| ECR repository | `oxxy-backend` |
| EKS cluster | `oxxy` |
| Namespace | `default` |
| App port | `8081` |
| Live URL | `http://<ALB-DNS>/products` |

## What Terraform creates

`main.tf` creates all of this in one apply:

1. ECR repository `oxxy-backend` (tag mutability `MUTABLE`, scan on push).
2. GitHub OIDC provider and IAM role `github-actions-ecr-push`, so Actions can push images without a long-lived AWS key. The trust policy uses the immutable subject `repo:amirrahi29@107117774/oxxy-backend@1439400639:*`.
3. VPC `10.0.0.0/16` with two public subnets, two private subnets, an internet gateway, and one NAT gateway. Public subnets are tagged `kubernetes.io/role/elb=1`. Private subnets are tagged `kubernetes.io/role/internal-elb=1`. Availability zones are filtered to `availability-zone` so the local zone `ap-south-1-ccu-1a` is not used.
4. EKS cluster `oxxy` (Kubernetes 1.35) and managed node group `oxxy-nodes` (`t3.medium`, desired 1, min 1, max 2) in the private subnets.
5. Cluster authentication mode `API_AND_CONFIG_MAP`, plus an EKS access entry so the GitHub role can update the `default` namespace.

```bash
terraform init
terraform apply
```

Confirm the plan updates the cluster in place. Do not apply a plan that says the cluster must be replaced.

## What the GitHub workflow does

`.github/workflows/deploy.yml` runs on every push to `main`:

1. Assumes `github-actions-ecr-push` with OIDC.
2. Builds the image from the `Dockerfile` and pushes two tags: `latest` and the git commit SHA.
3. Points `kubectl` at cluster `oxxy`.
4. Restarts deployment `oxxy-backend` so the new pod pulls `latest`.

The running pod does not notice a new `latest` image by itself. The restart step is what rolls the new code out.

## App image

The `Dockerfile` is multi-stage. The build stage uses `eclipse-temurin:17-jdk-alpine` and `./gradlew bootJar`. The runtime stage uses `eclipse-temurin:17-jre-alpine`, upgrades Alpine packages, and listens on 8081.

## Connect kubectl

```bash
aws eks update-kubeconfig --name oxxy --region ap-south-1
kubectl get nodes
```

## Install the app with Helm

The chart is the `oxxy-backend/` directory at the repo root.

- Image: `033193124350.dkr.ecr.ap-south-1.amazonaws.com/oxxy-backend:latest`
- `pullPolicy: Always`
- Service type `ClusterIP`, port `8081`
- One replica, requests `250m` CPU / `256Mi` memory

```bash
helm upgrade --install oxxy-backend ./oxxy-backend -n default
kubectl get pods -n default
```

## Install the AWS Load Balancer Controller

The controller creates the ALB from the Ingress. The chart version used here is **v3.6.2**. The IAM policy must be the v3.6.2 policy. An older policy is missing `ec2:GetSecurityGroupsForVpc` and the ALB create call fails.

```bash
curl -o iam_policy.json https://raw.githubusercontent.com/kubernetes-sigs/aws-load-balancer-controller/v3.6.2/docs/install/iam_policy.json

aws iam create-policy \
  --policy-name AWSLoadBalancerControllerIAMPolicy \
  --policy-document file://iam_policy.json
```

Create the EKS OIDC provider. The issuer for this cluster is:

`https://oidc.eks.ap-south-1.amazonaws.com/id/27DBA8D5C0EF41C7D20BFADBB480B4AF`

```bash
aws iam create-open-id-connect-provider \
  --url https://oidc.eks.ap-south-1.amazonaws.com/id/27DBA8D5C0EF41C7D20BFADBB480B4AF \
  --client-id-list sts.amazonaws.com \
  --thumbprint-list 9e99a48a9960b14926bb7f3b02e22da2b0ab7280
```

Save this as `trust-policy.json`, then create the role and attach the policy:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": {
        "Federated": "arn:aws:iam::033193124350:oidc-provider/oidc.eks.ap-south-1.amazonaws.com/id/27DBA8D5C0EF41C7D20BFADBB480B4AF"
      },
      "Action": "sts:AssumeRoleWithWebIdentity",
      "Condition": {
        "StringEquals": {
          "oidc.eks.ap-south-1.amazonaws.com/id/27DBA8D5C0EF41C7D20BFADBB480B4AF:aud": "sts.amazonaws.com",
          "oidc.eks.ap-south-1.amazonaws.com/id/27DBA8D5C0EF41C7D20BFADBB480B4AF:sub": "system:serviceaccount:kube-system:aws-load-balancer-controller"
        }
      }
    }
  ]
}
```

```bash
aws iam create-role \
  --role-name AmazonEKSLoadBalancerControllerRole \
  --assume-role-policy-document file://trust-policy.json

aws iam attach-role-policy \
  --role-name AmazonEKSLoadBalancerControllerRole \
  --policy-arn arn:aws:iam::033193124350:policy/AWSLoadBalancerControllerIAMPolicy
```

Create the service account and install the controller. `vpcId` is required because the nodes are private and cannot read the VPC id from instance metadata.

```bash
kubectl create serviceaccount aws-load-balancer-controller -n kube-system

kubectl annotate serviceaccount aws-load-balancer-controller \
  -n kube-system \
  eks.amazonaws.com/role-arn=arn:aws:iam::033193124350:role/AmazonEKSLoadBalancerControllerRole

helm repo add eks https://aws.github.io/eks-charts
helm repo update

helm upgrade --install aws-load-balancer-controller eks/aws-load-balancer-controller \
  -n kube-system \
  --set clusterName=oxxy \
  --set region=ap-south-1 \
  --set vpcId=vpc-00fb24ae6279f636c \
  --set serviceAccount.create=false \
  --set serviceAccount.name=aws-load-balancer-controller

kubectl get pods -n kube-system -l app.kubernetes.io/name=aws-load-balancer-controller
```

Wait until both pods are `1/1 Running`.

## Ingress

`oxxy-backend/templates/ingress.yaml` uses class `alb`, target type `ip` (so the ClusterIP service works), and scheme `internet-facing`. There is no `host`, so the ALB DNS name itself matches.

```bash
helm upgrade oxxy-backend ./oxxy-backend -n default
kubectl get ingress oxxy-backend -n default
```

`ADDRESS` fills in after a minute or two. Open it with `http`, not `https`. There is no listener on port 443.

```text
http://<ADDRESS>/products
```

A working response looks like:

```json
[{"id":1,"name":"Laptop1","price":999.99},{"id":2,"name":"Phone1","price":490.99},{"id":3,"name":"Headphones1","price":79.99}]
```

## Change the app later

Edit the code, commit, and push to `main`. Actions builds the image, pushes it, and restarts the deployment. You do not run the restart command yourself.

```bash
git add .
git commit -m "describe the change"
git push
```

Watch the run, then check the new pod:

```bash
kubectl rollout status deployment/oxxy-backend -n default
kubectl logs -n default -l app=oxxy-backend --tail=100
```

## Commands used day to day

```bash
kubectl get pods -n default
kubectl logs -n default -l app=oxxy-backend --tail=100
kubectl logs -n default -l app=oxxy-backend -f
kubectl get ingress oxxy-backend -n default
kubectl describe ingress oxxy-backend -n default
kubectl logs -n kube-system -l app.kubernetes.io/name=aws-load-balancer-controller --tail=50
```

`kubectl get logs` is not a command. Use `kubectl logs`.

## Notes

- The HorizontalPodAutoscaler in the chart scales pods, not EC2 nodes. There is no Cluster Autoscaler or Karpenter, and no metrics-server, so HPA targets stay unknown.
- The node group max size is 2. That cap does not create a new node when a second pod is added. One `t3.medium` fits the current requests.
- HTTP only. The browser shows "Not Secure" because there is no TLS certificate.
