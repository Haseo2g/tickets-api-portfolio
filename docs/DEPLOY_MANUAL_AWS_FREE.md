# Deploy manual pelo CloudFormation (AWS Free Account Plan)

**Estado em 07/11/2026:** o build local e os 6 testes foram executados; o ZIP local foi inspecionado. **Nenhum bucket, objeto S3, template empacotado ou stack foi criado por este procedimento.** As verificações feitas pelo proprietário no console cobrem a presença do assistente CloudFormation e do runtime Lambda Java 21. A criação de bucket S3 e de roles IAM pela stack continua **não verificada**. Esta é uma preparação, não uma autorização para criar recursos.

Região escolhida: **`us-east-2` (Ohio)**. A stack, o bucket de artefatos e a função devem ficar nessa região. O arquivo fonte é [`../template.yaml`](../template.yaml); **não envie esse template diretamente ao CloudFormation**, pois seu `CodeUri` aponta para um ZIP local.

## 1. Pré-requisitos e artefatos locais

- JDK 21, Maven Wrapper incluído no repositório e acesso ao Maven Central na primeira execução.
- Para `sam package`: AWS SAM CLI, AWS CLI ou credenciais temporárias reconhecidas pelo SAM, permissão de escrita no bucket S3 e uma sessão autenticada **sem Access Keys permanentes**. A autenticação CLI ainda não foi verificada. Se só houver acesso pelo console, pare antes do empacotamento.
- Para a stack: permissão de criar os recursos do template, inclusive uma role IAM de execução da Lambda e sua política. O acesso ao assistente CloudFormation não comprova essa permissão.

Na raiz do repositório, no PowerShell:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.11' # ajuste para seu JDK 21
./mvnw.cmd -B clean verify
Get-ChildItem deploy-artifacts
Get-FileHash deploy-artifacts/tickets-api-lambda.zip -Algorithm SHA256
```

Em Linux/macOS: `bash ./mvnw -B clean verify`. Maven Shade gera `target/tickets-api.jar`; Maven Assembly cria `deploy-artifacts/tickets-api-lambda.zip`, contendo `lib/tickets-api.jar`. O ZIP é o artefato de Lambda referenciado por `CodeUri`. A AWS documenta o uso de Maven Shade para Java e recomenda ZIP com o JAR em `lib/` quando o JAR inclui entradas de múltiplas versões. Não carregue o JAR solto como template CloudFormation. [`deploy-artifacts/`](../.gitignore) é ignorado pelo Git; `packaged-template.yaml` só aparecerá após um `sam package` bem-sucedido.

## 2. Bucket S3: verificação e preparo manual futuro

1. Antes de criar algo, confirme no console que **S3** e criação de bucket estão disponíveis no Free Account Plan dessa conta. Confira o saldo de créditos e as condições do plano.
2. Somente com autorização para criar recursos, abra S3 → **General purpose buckets** → **Create bucket**. Escolha `us-east-2`, um nome real e exclusivo para este projeto, mantenha **Block all public access**, ACLs desabilitadas e criptografia padrão SSE-S3. Não habilite Object Lock. Registre o nome do bucket para a remoção posterior. O bucket não pertence à stack.
3. O upload do pacote é feito por `sam package` na etapa seguinte. Ele envia o **ZIP que contém o JAR** ao bucket e escreve no template empacotado o endereço S3 real. Evite um `aws s3 cp` separado, que criaria uma cópia redundante sem atualizar `CodeUri`.

Um bucket público não é necessário: o objeto precisa ser legível pelos serviços AWS autorizados da mesma conta. Se S3 ou a escrita nesse bucket estiver bloqueado, este caminho de deploy por CloudFormation fica bloqueado. Não troque `CodeUri` por um endereço inventado.

## 3. Empacotar com AWS SAM — pendente até haver sessão CLI temporária

Instale SAM CLI e configure **uma sessão temporária permitida pela conta** em um perfil AWS CLI. O modo de obtenção dessa sessão depende do que sua conta oferece; não crie Access Keys permanentes para executar estes comandos. Substitua apenas os valores digitados em `Read-Host` por dados **reais**, após confirmar o bucket no console.

```powershell
$AwsRegion = 'us-east-2'
$AwsProfile = Read-Host 'Nome do perfil AWS CLI temporário já autenticado'
$ArtifactBucket = Read-Host 'Nome real do bucket S3 em us-east-2'

aws sts get-caller-identity --profile $AwsProfile --region $AwsRegion
aws s3api get-bucket-location --bucket $ArtifactBucket --profile $AwsProfile --region $AwsRegion
sam validate --template-file template.yaml --region $AwsRegion
sam package --template-file template.yaml --s3-bucket $ArtifactBucket --s3-prefix tickets-api-portfolio --output-template-file deploy-artifacts/packaged-template.yaml --region $AwsRegion --profile $AwsProfile
Select-String -Path deploy-artifacts/packaged-template.yaml -Pattern 'CodeUri:'
```

Confirme que `get-bucket-location` retornou `us-east-2` e que o `CodeUri` do arquivo gerado aponta para **o S3 real**. `sam package` é uma operação com efeito na AWS: faz upload do ZIP. Não foi executada neste projeto. Não execute `sam deploy`: o objetivo é levar o template empacotado ao assistente CloudFormation, após análise das permissões. Se não houver perfil temporário autenticado, **pare aqui**; o template empacotado ainda não existe.

## 4. Revisar e carregar no CloudFormation — pendente

1. No console CloudFormation, confirme `us-east-2`. Escolha **Create stack → With new resources (standard) → Choose an existing template → Upload a template file** e selecione **`deploy-artifacts/packaged-template.yaml`**, nunca `template.yaml` da raiz.
2. Esse upload pelo console pode criar um **bucket auxiliar próprio do CloudFormation** na região, separado do bucket de artefatos, e exige permissões S3 adicionais. Se o upload do console estiver bloqueado, uma opção é enviar o template empacotado ao bucket já criado e escolher **Amazon S3 URL**; use somente a URL real retornada pelo S3. Não há URL pronta neste guia.
3. Avance apenas para revisar o nome da stack, os recursos e as permissões. O SAM transforma `AWS::Serverless::*` em recursos CloudFormation. O console exige reconhecer **“I acknowledge that this template may create IAM resources”** (`CAPABILITY_IAM`). Essa caixa reconhece o risco; não concede permissões ausentes. Como `AWS::Serverless` é uma macro, a criação **direta sem change set** também pode exigir `CAPABILITY_AUTO_EXPAND`; prefira revisar um change set quando autorizado.
4. **Não clique em Submit/Create stack sem autorização explícita.** A validação local e a presença do assistente não garantem que a stack possa ser criada no Free Account Plan. Um change set pode ajudar a revisar mudanças, mas também é uma operação na conta e deve seguir a mesma autorização.

## 5. IAM e Role Manager: o principal bloqueio a testar

O SAM cria automaticamente uma **role de execução da Lambda** porque `TicketsFunction` não define `Role`. O ID lógico esperado é `TicketsFunctionRole`. A role recebe permissões básicas de escrita em CloudWatch Logs e a política inline deste template: somente `dynamodb:PutItem`, `GetItem`, `Scan` e `UpdateItem` na ARN de `TicketsTable`. A API também recebe permissões de invocação da Lambda geradas pelo SAM. O template não cria usuário IAM, Access Key, role OIDC nem uma role de serviço CloudFormation explícita.

**Quem publica é diferente de quem executa:** a identidade que cria a stack, ou uma role de serviço CloudFormation selecionada no assistente, precisa poder criar/atualizar/remover Lambda, HTTP API, DynamoDB, log group e a role/política IAM da Lambda, além de `iam:PassRole`. O bucket de artefatos requer permissões S3 para `sam package`. O upload do template pelo assistente pode exigir `cloudformation:CreateUploadBucket`, `s3:CreateBucket`, `PutObject`, `GetObject` e `ListBucket`. Não use `AdministratorAccess` ou políticas amplas como solução automática.

A documentação IAM atual **lista CloudFormation e Lambda entre os consoles que suportam Role Manager**, mas isso se refere ao fluxo do console. Para CloudFormation, o template de role listado pela AWS é `PowerUserRoleTemplate`, potencialmente amplo. Isso **não demonstra** que o Role Manager irá criar a role restrita descrita no template SAM nem que a conta Free Account Plan permite `iam:CreateRole` via CloudFormation. São mecanismos diferentes. Inspecione qualquer role oferecida no assistente e suas permissões antes de aceitá-la. Não a selecione apenas para contornar um erro de autorização.

Se a criação de `TicketsFunctionRole` for bloqueada:

- **Preferível:** manter o template e aguardar uma forma autorizada de criar uma role de execução com confiança em `lambda.amazonaws.com`, permissão básica de logs e as quatro ações DynamoDB restritas à tabela. Só então definir `Role` no SAM e revisar o efeito: `Policies` passa a ser ignorado quando `Role` é informado. Essa alternativa depende de uma role existente e aprovada; não foi implementada porque não há ARN real.
- Uma role criada automaticamente pelo console Lambda ou CloudFormation só serviria após inspecionar e, se necessário, reduzir suas permissões. O Role Manager pode oferecer acesso amplo; não assumir equivalência com a política restrita do projeto.
- Se nem a role restrita nem o bucket S3 puderem ser usados, **não há deploy deste template no CloudFormation por este caminho**. Isso não exige mudar a aplicação Java, mas adia a publicação.

## 6. Smoke test e diagnóstico — somente após uma stack autorizada

Se a stack chegar a `CREATE_COMPLETE`, leia o output real `ApiUrl` na aba **Outputs**. No terminal com `bash`, `curl` e `python3`:

```bash
bash scripts/smoke.sh 'URL_REAL_DO_OUTPUT_ApiUrl'
```

O roteiro cria um chamado **fictício**, verifica leitura, paginação e transições, e espera erros 409/404. Não foi executado contra a AWS. Se houver falha, consulte a aba **Events** do CloudFormation, o recurso com `CREATE_FAILED`, os logs `/aws/lambda/<FunctionName>` no CloudWatch e o cabeçalho `x-request-id` da resposta.

| Sintoma | Verificação |
|---|---|
| `CodeUri` local ou erro de artefato | Foi enviado o template **empacotado**? O objeto S3 real existe na mesma região e conta? |
| `AccessDenied` em S3 | Confira bucket, região e permissões de upload/leitura do perfil temporário. |
| `InsufficientCapabilities` | Revise o reconhecimento explícito de recursos IAM no assistente. |
| `CAPABILITY_AUTO_EXPAND` | A criação direta de template com transform SAM pode exigir essa capacidade; revise um change set ou a opção oferecida pelo console. |
| `AccessDenied`/`iam:CreateRole` ou `iam:PassRole` | A identidade/role da stack não pode criar ou passar a role; Role Manager do console não comprova essa permissão. |
| `CREATE_FAILED` na Lambda/API/tabela/logs | Leia o evento exato no CloudFormation; disponibilidade do serviço no console não garante permissão de provisionamento. |
| `ClassNotFoundException` | Inspecione o ZIP: deve conter `lib/tickets-api.jar` e a classe `dev/portfolio/tickets/ApiHandler.class` dentro do JAR. |
| HTTP 500 | Use `x-request-id` para localizar a requisição no CloudWatch; não registre corpos ou credenciais. |

## 7. Remoção completa e custos — se recursos forem criados no futuro

1. No CloudFormation em `us-east-2`, exclua a stack **após autorização** e espere `DELETE_COMPLETE`. A tabela DynamoDB usa `DeletionPolicy: Delete`: os dados são perdidos. Verifique se API, Lambda, log group e role gerada também desapareceram; se a exclusão falhar, trate `DELETE_FAILED` antes de concluir a limpeza.
2. No bucket de artefatos criado para este projeto, remova os objetos enviados por `sam package`. Se o bucket for **exclusivo** do projeto, esvazie-o, inclusive versões e delete markers se o versionamento tiver sido ativado, e exclua-o. Não apague buckets compartilhados.
3. Verifique o bucket auxiliar que o upload de template do CloudFormation pode ter criado. Remova somente objetos deste projeto; exclua o bucket apenas se for comprovadamente exclusivo e não servir a outras stacks. Uma role de serviço ou role do Role Manager criada fora da stack também exige revisão e remoção separada, quando exclusiva.
4. Confira Billing/Free Tier para consumo de créditos e recursos remanescentes. O Free Account Plan tem prazo e créditos limitados; a AWS informa que o plano termina após seis meses ou esgotamento dos créditos, o que vier primeiro. A existência de limites gratuitos não garante que todos os recursos ou operações deste template estejam disponíveis. Não faça upgrade para executar este guia.

## Fontes oficiais consultadas

- [Pacotes Java para Lambda](https://docs.aws.amazon.com/lambda/latest/dg/java-package.html)
- [SAM `package`](https://docs.aws.amazon.com/serverless-application-model/latest/developerguide/sam-cli-command-reference-sam-package.html) e [upload de arquivos locais](https://docs.aws.amazon.com/serverless-application-model/latest/developerguide/deploy-upload-local-files.html)
- [Função SAM e role implícita](https://docs.aws.amazon.com/serverless-application-model/latest/developerguide/sam-resource-function.html)
- [Criar stack no console CloudFormation](https://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/cfn-console-create-stack.html) e [reconhecimento IAM](https://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/control-access-with-iam.html)
- [Transform SAM](https://docs.aws.amazon.com/AWSCloudFormation/latest/TemplateReference/transform-aws-serverless.html) e [capacidade `CAPABILITY_AUTO_EXPAND`](https://docs.aws.amazon.com/AWSCloudFormation/latest/APIReference/API_CreateStack.html)
- [Role Manager](https://docs.aws.amazon.com/IAM/latest/UserGuide/id_roles_create_role-manager.html), [templates de role](https://docs.aws.amazon.com/IAM/latest/UserGuide/id_roles_create-role-template.html) e [role de serviço CloudFormation](https://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/using-iam-servicerole.html)
- [Criar bucket S3](https://docs.aws.amazon.com/AmazonS3/latest/userguide/create-bucket-overview.html) e [Free Account Plan](https://docs.aws.amazon.com/awsaccountbilling/latest/aboutv2/free-tier-plans.html)
