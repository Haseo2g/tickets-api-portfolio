# Deploy manual com AWS SAM e CloudFormation

**Estado em 07/11/2026:** o proprietário publicou a stack `tickets-api-demo` manualmente no console AWS em `us-east-2`. Após falhas de autorização e uma recuperação/atualização, informou `UPDATE_COMPLETE` às 18:03:32 (UTC−03:00). O [README](../README.md) e as [evidências](EVIDENCIAS_EXECUCAO.md) registram os testes HTTP e capturas recebidas. Este guia descreve como repetir ou remover a publicação; **nenhuma operação AWS foi executada ao atualizar esta documentação**.

O deploy pelo GitHub Actions permanece pendente porque a conta Free Account Plan informada bloqueia o provedor OIDC. Não houve upgrade, uso de Access Keys permanentes nem deploy automatizado.

## Recursos e artefatos

| Item | Valor usado |
|---|---|
| Conta e região | `161513061416`; `us-east-2` (Ohio) |
| Stack | `tickets-api-demo` |
| Bucket privado de artefatos | `haseo2g-tickets-api-artifacts-2026` |
| Pacote Lambda | `s3://haseo2g-tickets-api-artifacts-2026/tickets-api-lambda.zip` |
| Role de serviço da stack | `TicketsApiCloudFormationDeployRole` |
| Política da role de serviço | `TicketsApiCloudFormationDeployRolePolicy` |

O [template fonte](../template.yaml) usa `CodeUri: deploy-artifacts/tickets-api-lambda.zip`, um caminho **local**. Ele não pode ser enviado assim ao CloudFormation. O template carregado no console foi uma cópia local ignorada pelo Git, `deploy-artifacts/packaged-template.yaml`, cujo `CodeUri` aponta para o objeto S3 real. Não coloque URLs ou ARNs de outra conta no template fonte.

O SAM transforma `AWS::Serverless::Function` em Lambda, role de execução e permissões para o API Gateway; transforma `AWS::Serverless::HttpApi` em HTTP API e stage. A tabela DynamoDB e o log group são recursos explícitos. A role de serviço CloudFormation é **separada** da role de execução gerada pelo SAM.

## Repetir o preparo local

Pré-requisitos: JDK 21, Maven Wrapper do repositório e acesso ao Maven Central na primeira execução. Na raiz do projeto:

```powershell
./mvnw.cmd -B clean verify
Get-FileHash deploy-artifacts/tickets-api-lambda.zip -Algorithm SHA256
```

Maven Shade cria `target/tickets-api.jar`; Maven Assembly monta `deploy-artifacts/tickets-api-lambda.zip` com o JAR em `lib/`. Em Linux/macOS, use `bash ./mvnw -B clean verify`. Os testes unitários não precisam da AWS.

Para uma **nova versão**, publique o ZIP em um objeto S3 privado da mesma região, com identidade autorizada e credenciais temporárias, e gere um novo template com `CodeUri` real. O processo usado nesta implantação foi upload manual do ZIP e cópia do template. Não reutilize o mesmo nome de objeto sem conferir a versão de código que está sendo publicada.

Se você tiver uma sessão CLI temporária e SAM CLI, `sam package` é uma alternativa reproduzível: envia o ZIP e escreve o template empacotado. Ele **não foi o método utilizado no deploy registrado aqui**.

```powershell
sam package --template-file template.yaml --s3-bucket haseo2g-tickets-api-artifacts-2026 --s3-prefix tickets-api-portfolio --output-template-file deploy-artifacts/packaged-template.yaml --region us-east-2 --profile PERFIL_TEMPORARIO
```

O comando acima altera S3; execute apenas quando for publicar outra versão. O bucket de artefatos fica **fora da stack**.

## Publicação pelo console

1. Confirme conta `161513061416` e região `us-east-2`. Confira que o ZIP desejado existe no S3 privado e que o template empacotado aponta para esse objeto.
2. No CloudFormation, escolha **Criar pilha → Com novos recursos → Escolher um modelo existente → Fazer upload de um arquivo de modelo**. Selecione `deploy-artifacts/packaged-template.yaml`, não o `template.yaml` fonte. O upload do console pode usar um bucket auxiliar do CloudFormation.
3. Use o nome de stack desejado, por exemplo `tickets-api-demo` para uma atualização dessa stack. Revise o change set, os recursos e a role de serviço CloudFormation. Reconheça a criação da role IAM gerada pelo SAM (`CAPABILITY_IAM`) somente após inspecionar as permissões.
4. Depois de autorização para a operação, execute e acompanhe **Events** até `CREATE_COMPLETE` ou `UPDATE_COMPLETE`. Leia `ApiUrl` em **Outputs**. Não presuma sucesso a partir do upload do template.

Para novos ambientes, confirme as permissões e a compatibilidade com o plano da conta antes de criar recursos. A disponibilidade do assistente não garante que cada operação será permitida.

## IAM: ajustes encontrados e limites

A role de serviço `TicketsApiCloudFormationDeployRole` foi criada manualmente para publicar a stack. A política de publicação `TicketsApiCloudFormationDeployRolePolicy` não equivale à role de execução da Lambda. O template concede a esta última as ações `dynamodb:PutItem`, `GetItem`, `Scan` e `UpdateItem` apenas na tabela da stack, além das permissões básicas de logs do SAM.

Durante a criação, foram necessários três ajustes informados pelo proprietário:

1. `cloudformation:CreateChangeSet` no recurso do transform `arn:aws:cloudformation:us-east-2:aws:transform/Serverless-2016-10-31`.
2. `apigateway:POST` no recurso de tags usado ao criar a HTTP API.
3. `apigateway:TagResource` para o stage da HTTP API.

O terceiro ajuste precedeu a recuperação/atualização com `UPDATE_COMPLETE`. Não altere permissões da role sem revisar eventos reais do CloudFormation. O JSON local criado antes do deploy em `deploy-artifacts/` está ignorado pelo Git e **não deve ser apresentado como a política final confirmada**, pois não contém essas correções.

Permissões do API Gateway para recursos com ID gerado pelo serviço não ficaram restritas a uma única API antes da criação. Não descreva a política como privilégio mínimo absoluto. A identidade humana que seleciona a role também precisa, separadamente, de permissões CloudFormation e `iam:PassRole` para **CloudFormation**. Quem pode operar uma stack associada a essa role poderá usar as permissões dela nas operações da stack; restrinja o acesso à stack.

## Automação futura

O [workflow Deploy](../.github/workflows/deploy.yml) continua somente com `workflow_dispatch`. Para utilizá-lo futuramente, a conta precisará permitir um provedor OIDC `token.actions.githubusercontent.com` com audience `sts.amazonaws.com` e uma role de publicação cuja confiança restrinja o `sub` ao repositório `Haseo2g/tickets-api-portfolio` e ao GitHub Environment `demo`. Nesse environment, restrinja a implantação à branch `main` e configure as variables `AWS_ROLE_ARN`, `AWS_REGION=us-east-2`, `STACK_NAME=tickets-api-demo` e `SAM_ARTIFACT_BUCKET=haseo2g-tickets-api-artifacts-2026`. Revise permissões e regras do environment antes de qualquer execução. Não substitua OIDC por chaves permanentes nem apresente o deploy manual já realizado como automatizado.

## Smoke test e diagnóstico

O proprietário informou testes manuais de `GET /health`, `POST /tickets`, `GET /tickets/{id}`, `GET /tickets`, transições `OPEN → IN_PROGRESS → CLOSED` e erro HTTP 409. Veja [respostas e capturas](EVIDENCIAS_EXECUCAO.md). Paginação com múltiplas páginas não foi testada manualmente na AWS.

Para repetir um smoke test completo em uma stack autorizada, após obter `ApiUrl` real:

```bash
bash scripts/smoke.sh 'https://URL_REAL_DO_OUTPUT_ApiUrl'
```

O script usa `curl` e `python3`, cria um chamado fictício, verifica consulta, listagem e transições, e espera erros 409 e 404. **O script automatizado não foi executado contra a stack nesta documentação.** Se houver falha, leia **Events** no CloudFormation e pesquise o `requestId` no grupo `/aws/lambda/tickets-api-demo-TicketsFunction-*` do CloudWatch. Não registre corpos, credenciais ou descrições em logs de diagnóstico.

| Sintoma | Verificação |
|---|---|
| `CodeUri` local | Envie o template empacotado com objeto S3 real na região da Lambda. |
| `AccessDenied` no S3 | Confira objeto, criptografia e permissões de leitura da identidade de publicação. |
| `InsufficientCapabilities` | Revise o reconhecimento dos recursos IAM gerados pelo SAM. |
| `CreateChangeSet` negado | Confira permissão no ARN do transform SAM, além da permissão na stack. |
| API Gateway negado em tags/stage | Revise `apigateway:POST` no recurso de tags e `apigateway:TagResource` para o stage conforme o evento exato. |
| `iam:CreateRole` ou `iam:PassRole` negado | Separe role de serviço CloudFormation da role de execução da Lambda. |
| HTTP 500 | Use `x-request-id` e o CloudWatch; não exponha stack trace ao cliente. |

## Custos e remoção completa

Free Account Plan não garante custo zero: créditos, prazo, limites e consumo variam. O template usa DynamoDB sob demanda, Lambda 512 MB/10 s, throttling e log retention de sete dias; não há VPC, NAT ou balanceador. A API é pública, sem autenticação; use apenas dados fictícios e remova a demonstração após apresentá-la.

1. No CloudFormation em `us-east-2`, exclua `tickets-api-demo` após decidir descartar os dados e espere `DELETE_COMPLETE`. A tabela usa `DeletionPolicy: Delete` e `UpdateReplacePolicy: Delete`; seus chamados serão perdidos.
2. Confirme que Lambda, HTTP API, tabela, log group e role de execução gerada sumiram. Se houver `DELETE_FAILED`, trate o recurso apontado em **Events** antes de concluir a limpeza.
3. Remova os objetos do bucket `haseo2g-tickets-api-artifacts-2026`. Se for exclusivo deste projeto, esvazie-o, incluindo versões/delete markers se aplicável, e exclua-o. Não apague objetos ou buckets de outras stacks.
4. Revise o bucket auxiliar de upload do CloudFormation e remova apenas artefatos deste projeto. Revise separadamente `TicketsApiCloudFormationDeployRole` e sua política; elas foram criadas fora da stack e só devem ser removidas se não servirem a outra implantação.
5. Confira Billing/Free Tier e recursos remanescentes na conta.

## Fontes oficiais

- [Pacotes Java para Lambda](https://docs.aws.amazon.com/lambda/latest/dg/java-package.html)
- [`sam package`](https://docs.aws.amazon.com/serverless-application-model/latest/developerguide/sam-cli-command-reference-sam-package.html)
- [Recursos CloudFormation gerados pelo SAM](https://docs.aws.amazon.com/serverless-application-model/latest/developerguide/sam-specification-generated-resources.html)
- [Role de serviço CloudFormation](https://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/using-iam-servicerole.html)
- [Controle de acesso e transform SAM](https://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/control-access-with-iam.html)
- [Ações de API Gateway](https://docs.aws.amazon.com/service-authorization/latest/reference/list_apigateway.html)
- [Free Account Plan](https://docs.aws.amazon.com/awsaccountbilling/latest/aboutv2/free-tier-plans.html)
